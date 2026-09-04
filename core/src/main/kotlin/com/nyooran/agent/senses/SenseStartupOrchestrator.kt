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
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase 5 L5-02: Isolated and retryable startup orchestrator.
 *
 * Wraps [SenseRuntime] with a phased startup sequence that:
 * - Gives every attempt a generation ID
 * - Makes each attempt own all resources it creates
 * - Closes or safely reuses previous resources before allocating new
 * - Prevents stale callbacks from mutating current state
 * - Tests failure and retry at every startup phase
 *
 * Each [StartupPhase] can fail independently. On failure, the orchestrator
 * cleans up resources from the current generation before allowing a retry.
 * Stale generation callbacks are ignored — only the current generation
 * can mutate state.
 */
class SenseStartupOrchestrator(
    private val runtime: SenseRuntime,
) {

    private val _startupState = MutableStateFlow<StartupAttemptState>(StartupAttemptState.Idle)
    val startupState: StateFlow<StartupAttemptState> = _startupState.asStateFlow()

    private val attemptMutex = Mutex()
    private var currentAttempt: Long = 0

    /**
     * Runs the startup sequence. Each phase is executed in order.
     * If any phase fails, the sequence stops and [startupState] reflects
     * the failure. The caller can retry by calling [start] again.
     *
     * Only one startup attempt can run at a time. Concurrent calls
     * are serialized by [attemptMutex].
     *
     * @param phases ordered list of named phases to execute
     * @return true if all phases succeeded, false if any failed
     */
    suspend fun start(phases: List<StartupPhase>): Boolean {
        attemptMutex.withLock {
            // Reset any previous attempt
            if (currentAttempt > 0) {
                runtime.reset()
            }

            val gen = runtime.startGeneration()
            currentAttempt = gen
            _startupState.value = StartupAttemptState.Starting(gen)

            for ((index, phase) in phases.withIndex()) {
                // Check if we're still the current generation
                if (currentAttempt != gen) {
                    // A newer generation has started; abort this one
                    return false
                }

                _startupState.value = StartupAttemptState.PhaseRunning(
                    generation = gen,
                    phaseName = phase.name,
                    phaseIndex = index,
                    totalPhases = phases.size,
                )

                try {
                    phase.execute(runtime, gen)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Phase failed — clean up and report
                    _startupState.value = StartupAttemptState.PhaseFailed(
                        generation = gen,
                        phaseName = phase.name,
                        phaseIndex = index,
                        totalPhases = phases.size,
                        error = e.message ?: e::class.simpleName ?: "unknown error",
                    )
                    runtime.markFailed(e.message ?: "phase ${phase.name} failed")
                    // Clean up resources from this failed attempt
                    withContext(NonCancellable) {
                        runtime.reset()
                    }
                    return false
                }
            }

            // All phases succeeded
            if (currentAttempt == gen) {
                runtime.markReady()
                _startupState.value = StartupAttemptState.Ready(gen)
                return true
            }
            return false
        }
    }

    /**
     * Cancels any in-progress startup attempt.
     * The current generation's resources are cleaned up.
     */
    suspend fun cancel() {
        attemptMutex.withLock {
            currentAttempt = 0
            _startupState.value = StartupAttemptState.Cancelled
            withContext(NonCancellable) {
                runtime.reset()
            }
        }
    }

    /**
     * Whether the orchestrator is currently in a ready state.
     */
    val isReady: Boolean get() = _startupState.value is StartupAttemptState.Ready

    /**
     * The current generation ID, or 0 if no attempt has been made.
     */
    val currentGeneration: Long get() = currentAttempt
}

/**
 * A single phase of the startup sequence.
 * Each phase receives the runtime and generation ID.
 * If the phase throws, the startup fails at that phase.
 */
class StartupPhase(
    val name: String,
    private val block: suspend (runtime: SenseRuntime, generation: Long) -> Unit,
) {
    suspend fun execute(runtime: SenseRuntime, generation: Long) = block(runtime, generation)
}

/**
 * Observable state of a startup attempt.
 */
sealed class StartupAttemptState {
    data object Idle : StartupAttemptState()

    data class Starting(val generation: Long) : StartupAttemptState()

    data class PhaseRunning(
        val generation: Long,
        val phaseName: String,
        val phaseIndex: Int,
        val totalPhases: Int,
    ) : StartupAttemptState() {
        val progress: Float get() = if (totalPhases > 0) (phaseIndex + 1).toFloat() / totalPhases else 0f
    }

    data class PhaseFailed(
        val generation: Long,
        val phaseName: String,
        val phaseIndex: Int,
        val totalPhases: Int,
        val error: String,
    ) : StartupAttemptState()

    data class Ready(val generation: Long) : StartupAttemptState()

    data object Cancelled : StartupAttemptState()
}
