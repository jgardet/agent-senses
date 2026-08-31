package com.nyooran.agent.senses

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /**
     * Bind an endpoint to the session.
     *
     * If an endpoint with the same ID is already bound, it is replaced
     * atomically: the old endpoint's active operations are cancelled
     * and it is disconnected before the new one is connected.
     */
    suspend fun bind(endpoint: SenseEndpoint) = bindMutex.withLock {
        val id = endpoint.profile.endpointId
        val existing = endpoints[id]
        if (existing != null) {
            existing.disconnect()
        }
        endpoints[id] = endpoint
        refreshProfiles()
    }

    /**
     * Unbind an endpoint from the session.
     *
     * Cancels active operations and disconnects the endpoint.
     */
    suspend fun unbind(endpointId: EndpointId) = bindMutex.withLock {
        val endpoint = endpoints.remove(endpointId)
        endpoint?.disconnect()
        refreshProfiles()
    }

    /**
     * Unbind all endpoints.
     */
    suspend fun unbindAll() = bindMutex.withLock {
        for (endpoint in endpoints.values) {
            runCatching { endpoint.disconnect() }
        }
        endpoints.clear()
        refreshProfiles()
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
