package com.nyooran.agent.senses

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase 2 session endpoint registry (C2-05).
 *
 * Binds one or more sense endpoints to an agent session. The registry
 * is the single source of truth for which endpoints are available,
 * what they support, and how to route operations.
 *
 * Rules (from AGENT_SENSES_ARCHITECTURE.md AD-7, AD-9):
 * - Bind/unbind/replacement is atomic and observable.
 * - No automatic simulator/fallback binding based on build type or failure.
 * - When multiple endpoints support a capability, the caller must
 *   select one explicitly; the registry returns the eligible list
 *   rather than silently choosing.
 * - Active operations are cancelled on unbind/replacement.
 */

/**
 * A sense endpoint — the interface that backends implement.
 *
 * Each endpoint exposes its [profile] and capability methods.
 * Methods are suspending and cancellable. Unsupported capabilities
 * throw [SensesError.Unavailable] (or return a typed failure when
 * called through the registry).
 */
interface SenseEndpoint {
    val profile: SenseProfile

    suspend fun connect()
    suspend fun disconnect()

    suspend fun audioInput(request: AudioInputRequest): AudioInputResult
    suspend fun audioOutput(request: AudioOutputRequest): AudioOutputResult
    suspend fun imageInput(request: ImageInputRequest): ImageInputResult
    suspend fun visualOutput(request: VisualOutputRequest): VisualOutputResult
    suspend fun textOutput(request: TextOutputRequest): TextOutputResult
    suspend fun textInput(request: TextInputRequest): TextInputResult
    suspend fun interactionInput(request: InteractionInputRequest): InteractionInputResult
    suspend fun statusInput(request: StatusInputRequest): StatusInputResult
}

/**
 * Registry of endpoints bound to a session.
 */
class SenseEndpointRegistry {

    private val endpoints = ConcurrentHashMap<EndpointId, SenseEndpoint>()
    private val _endpointProfiles = MutableStateFlow<List<SenseProfile>>(emptyList())
    val endpointProfiles: StateFlow<List<SenseProfile>> = _endpointProfiles.asStateFlow()

    private val operationCounter = AtomicLong(0)
    private val bindMutex = Mutex()

    /** Active operations per endpoint, keyed by operation ID. */
    private val activeOperations = ConcurrentHashMap<EndpointId, MutableMap<String, Job>>()

    /** Observable active operations. */
    private val _operations = MutableStateFlow<Map<EndpointId, Set<String>>>(emptyMap())
    val operations: StateFlow<Map<EndpointId, Set<String>>> = _operations.asStateFlow()

    /** Grace period for active operations to complete before forced cancellation. */
    var cancellationGracePeriodMillis: Long = 1000L

    /**
     * Bind an endpoint to the session.
     *
     * If an endpoint with the same ID is already bound, it is replaced
     * atomically: the old endpoint's active operations are cancelled
     * (with a grace period), it is disconnected, and then the new one
     * is connected.
     */
    suspend fun bind(endpoint: SenseEndpoint) = bindMutex.withLock {
        val id = endpoint.profile.endpointId
        val existing = endpoints[id]
        if (existing != null) {
            awaitCancellationAndDisconnect(id, existing)
        }
        endpoints[id] = endpoint
        refreshProfiles()
    }

    /**
     * Unbind an endpoint from the session.
     *
     * Cancels active operations (with a grace period) and disconnects
     * the endpoint.
     */
    suspend fun unbind(endpointId: EndpointId) = bindMutex.withLock {
        val endpoint = endpoints.remove(endpointId)
        if (endpoint != null) {
            awaitCancellationAndDisconnect(endpointId, endpoint)
        }
        refreshProfiles()
    }

    /**
     * Unbind all endpoints.
     *
     * Runs disconnect cleanup in [NonCancellable] context so a cancelled
     * caller cannot interrupt endpoint teardown.
     */
    suspend fun unbindAll() = withContext(NonCancellable) {
        bindMutex.withLock {
            for ((id, endpoint) in endpoints.entries) {
                runCatching { awaitCancellationAndDisconnect(id, endpoint) }
            }
            endpoints.clear()
            refreshProfiles()
        }
    }

    /**
     * Cancel active operations on [endpointId], wait for them to complete
     * (up to [cancellationGracePeriodMillis]), then disconnect the endpoint.
     *
     * Disconnect runs in [NonCancellable] context so that endpoint cleanup
     * completes even when the caller has been cancelled.
     */
    private suspend fun awaitCancellationAndDisconnect(endpointId: EndpointId, endpoint: SenseEndpoint) {
        val ops = activeOperations.remove(endpointId)
        if (ops != null && ops.isNotEmpty()) {
            for (job in ops.values) {
                job.cancel()
            }
            withTimeoutOrNull(cancellationGracePeriodMillis) {
                for (job in ops.values) {
                    job.join()
                }
            }
        }
        withContext(NonCancellable) {
            runCatching { endpoint.disconnect() }
        }
    }

    /**
     * Get the endpoint for [endpointId], or null if not bound.
     */
    fun get(endpointId: EndpointId): SenseEndpoint? = endpoints[endpointId]

    /**
     * List all endpoints that support [capability].
     *
     * If the list has more than one entry, the caller must select one
     * explicitly. The registry never silently chooses.
     */
    fun endpointsForCapability(capability: SenseCapability): List<SenseEndpoint> =
        endpoints.values.filter { it.profile.supports(capability) }

    /**
     * Generate a unique operation ID for provenance.
     */
    fun nextOperationId(): String = "op-${operationCounter.incrementAndGet()}"

    /**
     * Register an active operation for [endpointId].
     *
     * The operation's [Job] is tracked so it can be cancelled when the
     * endpoint is unbound or replaced. Call [completeOperation] when done.
     */
    fun registerOperation(endpointId: EndpointId, operationId: String, job: Job) {
        activeOperations.getOrPut(endpointId) { ConcurrentHashMap() }[operationId] = job
        refreshOperations()
    }

    /**
     * Mark an operation as completed and remove it from tracking.
     */
    fun completeOperation(endpointId: EndpointId, operationId: String) {
        activeOperations[endpointId]?.remove(operationId)
        refreshOperations()
    }

    private fun refreshOperations() {
        _operations.value = activeOperations.mapValues { it.value.keys.toSet() }
    }

    /**
     * Count active operations for [endpointId].
     */
    fun activeOperationCount(endpointId: EndpointId): Int =
        activeOperations[endpointId]?.size ?: 0

    /**
     * Resolve an endpoint for [capability], requiring explicit selection
     * when multiple endpoints are eligible.
     *
     * Returns the single endpoint if exactly one supports the capability,
     * or [ResolveResult.Ambiguous] with the eligible list if more than one.
     */
    fun resolve(capability: SenseCapability, target: EndpointId? = null): ResolveResult {
        if (target != null) {
            val endpoint = endpoints[target]
                ?: return ResolveResult.NotFound(target)
            if (!endpoint.profile.supports(capability)) {
                return ResolveResult.Unsupported(endpoint, capability)
            }
            return ResolveResult.Resolved(endpoint)
        }
        val eligible = endpointsForCapability(capability)
        return when {
            eligible.isEmpty() -> ResolveResult.NoEndpoint(capability)
            eligible.size == 1 -> ResolveResult.Resolved(eligible[0])
            else -> ResolveResult.Ambiguous(eligible)
        }
    }

    private fun refreshProfiles() {
        _endpointProfiles.value = endpoints.values.map { it.profile }.sortedBy { it.endpointId.value }
    }
}

/**
 * Result of resolving an endpoint for a capability.
 */
sealed interface ResolveResult {
    /** A single endpoint was resolved. */
    data class Resolved(val endpoint: SenseEndpoint) : ResolveResult

    /** Multiple endpoints support the capability; the caller must choose. */
    data class Ambiguous(val eligible: List<SenseEndpoint>) : ResolveResult

    /** No bound endpoint supports the capability. */
    data class NoEndpoint(val capability: SenseCapability) : ResolveResult

    /** The requested endpoint ID is not bound. */
    data class NotFound(val endpointId: EndpointId) : ResolveResult

    /** The endpoint is bound but does not support the capability. */
    data class Unsupported(val endpoint: SenseEndpoint, val capability: SenseCapability) : ResolveResult
}
