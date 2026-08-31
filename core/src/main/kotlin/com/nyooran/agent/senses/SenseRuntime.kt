package com.nyooran.agent.senses

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase 5 L5-01: Application runtime owner.
 *
 * Explicitly owns the endpoint registry, model scheduler, and all resources
 * that need ordered construction and teardown. Makes shutdown suspendable
 * and idempotent. Preserves retained runtime across configuration changes.
 *
 * Construction order:
 *   1. Endpoint registry
 *   2. Endpoints (bound by caller)
 *   3. Models (transcription, vision, TTS)
 *   4. Workflows
 *
 * Teardown order (reverse):
 *   1. Cancel all active operations
 *   2. Disconnect endpoints
 *   3. Close models
 *   4. Clear registry
 *
 * The runtime is designed to survive Activity configuration changes
 * (rotation). The caller holds a reference to the [SenseRuntime] in a
 * component that outlives the Activity (e.g. AndroidViewModel, Application,
 * or a retained fragment). [shutdown] is only called when the session is
 * truly finished.
 */
class SenseRuntime {

    /** Monotonically increasing generation ID for startup attempts. */
    private val generationCounter = AtomicLong(0)
    val generation: Long get() = generationCounter.get()

    private val _state = MutableStateFlow<RuntimeState>(RuntimeState.Idle)
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

    val registry = SenseEndpointRegistry()

    /** Active operation jobs, tracked for cancellation on shutdown. */
    private val activeOperations = mutableMapOf<String, Job>()
    private val operationsMutex = Mutex()

    /** Supervisor scope for runtime-managed coroutines. */
    private var scope: kotlinx.coroutines.CoroutineScope? = null

    /** Registered closeables for teardown (in registration order). */
    private val closeables = mutableListOf<AutoCloseable>()
    private val closeablesMutex = Mutex()

    /** True after [shutdown] has been called. */
    @Volatile
    private var shutdown = false

    /**
     * Starts a new generation. Returns the generation ID.
     * Each generation owns all resources it creates.
     */
    fun startGeneration(): Long {
        val gen = generationCounter.incrementAndGet()
        _state.value = RuntimeState.Starting(gen)
        if (scope == null) {
            scope = kotlinx.coroutines.CoroutineScope(SupervisorJob())
        }
        return gen
    }

    /**
     * Marks the current generation as ready.
     */
    fun markReady() {
        _state.value = RuntimeState.Ready(generation)
    }

    /**
     * Marks the current generation as failed.
     */
    fun markFailed(error: String) {
        _state.value = RuntimeState.Failed(generation, error)
    }

    /**
     * Registers a [closeable] that will be closed during shutdown.
     * Resources are closed in reverse registration order (LIFO).
     */
    suspend fun registerCloseable(closeable: AutoCloseable) {
        closeablesMutex.withLock { closeables.add(closeable) }
    }

    /**
     * Tracks an active operation for cancellation on shutdown.
     * Returns the operation ID for later removal.
     */
    suspend fun trackOperation(operationId: String, job: Job) {
        operationsMutex.withLock { activeOperations[operationId] = job }
    }

    /**
     * Removes a completed operation from tracking.
     */
    suspend fun untrackOperation(operationId: String) {
        operationsMutex.withLock { activeOperations.remove(operationId) }
    }

    /**
     * Cancels a specific operation by ID.
     */
    suspend fun cancelOperation(operationId: String): Boolean {
        val job = operationsMutex.withLock { activeOperations.remove(operationId) }
        job?.cancel()
        return job != null
    }

    /**
     * Suspends and shuts down the runtime.
     *
     * Teardown order:
     *   1. Cancel all active operations
     *   2. Disconnect all endpoints (via registry)
     *   3. Close registered closeables (LIFO)
     *   4. Cancel the scope
     *   5. Clear the registry
     *
     * Idempotent: calling [shutdown] multiple times is safe.
     */
    suspend fun shutdown() {
        if (shutdown) return
        shutdown = true
        _state.value = RuntimeState.Stopping(generation)

        // 1. Cancel all active operations
        val ops = operationsMutex.withLock {
            val copy = activeOperations.toMap()
            activeOperations.clear()
            copy
        }
        ops.values.forEach { it.cancel() }

        // 2. Disconnect all endpoints
        runCatching {
            registry.unbindAll()
        }

        // 3. Close registered closeables (LIFO)
        val toClose = closeablesMutex.withLock {
            val copy = closeables.reversed()
            closeables.clear()
            copy
        }
        for (closeable in toClose) {
            runCatching { closeable.close() }
        }

        // 4. Cancel the scope
        scope?.cancel()
        scope = null

        _state.value = RuntimeState.Stopped(generation)
    }

    /**
     * Non-suspending shutdown for use from lifecycle callbacks.
     * Blocks until shutdown is complete or times out.
     */
    fun shutdownBlocking(timeoutMillis: Long = 5000) {
        runBlocking {
            withTimeoutOrNull(timeoutMillis) { shutdown() }
        }
    }

    /**
     * Resets the runtime for a new startup attempt, keeping the same
     * generation counter. This is used when retrying after a failure.
     */
    suspend fun reset() {
        if (!shutdown) shutdown()
        shutdown = false
        _state.value = RuntimeState.Idle
    }

    /**
     * Whether the runtime is currently in a ready state.
     */
    val isReady: Boolean get() = _state.value is RuntimeState.Ready

    /**
     * Number of currently active operations.
     */
    val activeOperationCount: Int get() = activeOperations.size
}

/**
 * Observable state of the [SenseRuntime].
 */
sealed class RuntimeState {
    /** Initial state, before any generation has started. */
    data object Idle : RuntimeState()

    /** A startup attempt is in progress. */
    data class Starting(val generation: Long) : RuntimeState()

    /** The runtime is ready for operations. */
    data class Ready(val generation: Long) : RuntimeState()

    /** The startup attempt failed. */
    data class Failed(val generation: Long, val error: String) : RuntimeState()

    /** Shutdown is in progress. */
    data class Stopping(val generation: Long) : RuntimeState()

    /** Shutdown is complete. */
    data class Stopped(val generation: Long) : RuntimeState()
}
