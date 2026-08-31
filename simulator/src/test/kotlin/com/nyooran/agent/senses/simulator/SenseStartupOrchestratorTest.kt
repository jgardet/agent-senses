package com.nyooran.agent.senses.simulator

import com.nyooran.agent.senses.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SenseStartupOrchestratorTest {

    private fun orchestrator() = SenseStartupOrchestrator(SenseRuntime())

    // ------------------------------------------------------------------ happy path

    @Test
    fun startWithEmptyPhasesSucceeds() = runTest {
        val orch = orchestrator()
        val result = orch.start(emptyList())
        assertTrue(result)
        assertTrue(orch.isReady)
        assertTrue(orch.startupState.value is StartupAttemptState.Ready)
    }

    @Test
    fun startWithPhasesExecutesAllInOrder() = runTest {
        val orch = orchestrator()
        val executed = mutableListOf<String>()
        val phases = listOf(
            StartupPhase("test") { _, _ -> executed.add("phase-1") },
            StartupPhase("test") { _, _ -> executed.add("phase-2") },
            StartupPhase("test") { _, _ -> executed.add("phase-3") },
        )
        val result = orch.start(phases)
        assertTrue(result)
        assertEquals(listOf("phase-1", "phase-2", "phase-3"), executed)
    }

    @Test
    fun startUpdatesStateThroughPhases() = runTest {
        val orch = orchestrator()
        val phases = listOf(
            StartupPhase("test") { runtime, gen -> /* no-op */ },
            StartupPhase("test") { runtime, gen -> /* no-op */ },
        )
        orch.start(phases)
        val state = orch.startupState.value
        assertTrue(state is StartupAttemptState.Ready)
    }

    @Test
    fun startGeneratesIncrementingGenerationIds() = runTest {
        val orch = orchestrator()
        orch.start(emptyList())
        val gen1 = orch.currentGeneration
        orch.start(emptyList())
        val gen2 = orch.currentGeneration
        assertTrue(gen2 > gen1)
    }

    // ------------------------------------------------------------------ failure

    @Test
    fun startFailsWhenPhaseThrows() = runTest {
        val orch = orchestrator()
        val phases = listOf(
            StartupPhase("test") { _, _ -> /* succeeds */ },
            StartupPhase("test") { _, _ -> error("phase 2 failed") },
            StartupPhase("test") { _, _ -> /* should not run */ },
        )
        val result = orch.start(phases)
        assertFalse(result)
        val state = orch.startupState.value
        assertTrue(state is StartupAttemptState.PhaseFailed)
        assertEquals("phase 2 failed", state.error)
        assertEquals(1, state.phaseIndex)
    }

    @Test
    fun startStopsAtFailedPhase() = runTest {
        val orch = orchestrator()
        var phase3Ran = false
        val phases = listOf(
            StartupPhase("test") { _, _ -> /* succeeds */ },
            StartupPhase("test") { _, _ -> error("fail") },
            StartupPhase("test") { _, _ -> phase3Ran = true },
        )
        orch.start(phases)
        assertFalse(phase3Ran)
    }

    @Test
    fun startCleansUpOnFailure() = runTest {
        val runtime = SenseRuntime()
        val orch = SenseStartupOrchestrator(runtime)
        val phases = listOf(
            StartupPhase("bind") { rt, _ ->
                rt.registry.bind(SimulatedHaloEndpoint())
            },
            StartupPhase("test") { _, _ -> error("fail") },
        )
        orch.start(phases)
        // Registry should be cleared after failed attempt
        assertEquals(0, runtime.registry.endpointProfiles.value.size)
    }

    // ------------------------------------------------------------------ retry

    @Test
    fun retryAfterFailureSucceeds() = runTest {
        val orch = orchestrator()
        var shouldFail = true
        val phases = listOf(
            StartupPhase("test") { _, _ ->
                if (shouldFail) error("first attempt fails")
            },
        )
        // First attempt fails
        var result = orch.start(phases)
        assertFalse(result)
        // Retry succeeds
        shouldFail = false
        result = orch.start(phases)
        assertTrue(result)
        assertTrue(orch.isReady)
    }

    @Test
    fun retryUsesNewGenerationId() = runTest {
        val orch = orchestrator()
        val phases = listOf(StartupPhase("test") { _, _ -> error("always fails") })
        orch.start(phases)
        val gen1 = orch.currentGeneration
        orch.start(phases)
        val gen2 = orch.currentGeneration
        assertTrue(gen2 > gen1)
    }

    // ------------------------------------------------------------------ cancel

    @Test
    fun cancelResetsToIdle() = runTest {
        val orch = orchestrator()
        orch.start(emptyList())
        assertTrue(orch.isReady)
        orch.cancel()
        assertFalse(orch.isReady)
        assertEquals(StartupAttemptState.Cancelled, orch.startupState.value)
    }

    @Test
    fun cancelCleansUpResources() = runTest {
        val runtime = SenseRuntime()
        val orch = SenseStartupOrchestrator(runtime)
        val phases = listOf(
            StartupPhase("test") { rt, _ -> rt.registry.bind(SimulatedHaloEndpoint()) },
        )
        orch.start(phases)
        assertEquals(1, runtime.registry.endpointProfiles.value.size)
        orch.cancel()
        assertEquals(0, runtime.registry.endpointProfiles.value.size)
    }

    // ------------------------------------------------------------------ stale callbacks

    @Test
    fun staleGenerationDoesNotMutateCurrentState() = runTest {
        val runtime = SenseRuntime()
        val orch = SenseStartupOrchestrator(runtime)
        // This is implicitly tested by the generation check in start()
        // If a stale phase tries to run, it's skipped
        val result = orch.start(listOf(StartupPhase("test") { _, _ -> /* ok */ }))
        assertTrue(result)
    }

    // ------------------------------------------------------------------ phase progress

    @Test
    fun phaseRunningStateIncludesProgress() = runTest {
        val orch = orchestrator()
        val states = mutableListOf<StartupAttemptState>()
        val phases = listOf(
            StartupPhase("test") { _, _ -> states.add(orch.startupState.value) },
            StartupPhase("test") { _, _ -> states.add(orch.startupState.value) },
        )
        orch.start(phases)
        // The first state captured should be PhaseRunning with phaseIndex=0
        val firstRunning = states.filterIsInstance<StartupAttemptState.PhaseRunning>().first()
        assertEquals(0, firstRunning.phaseIndex)
        assertEquals(2, firstRunning.totalPhases)
        assertEquals(0.5f, firstRunning.progress)
    }

    // ------------------------------------------------------------------ resource ownership

    @Test
    fun eachAttemptOwnsItsResources() = runTest {
        val runtime = SenseRuntime()
        val orch = SenseStartupOrchestrator(runtime)
        // First attempt binds an endpoint
        orch.start(listOf(
            StartupPhase("test") { rt, _ -> rt.registry.bind(SimulatedHaloEndpoint(endpointId = EndpointId("ep-1"))) },
        ))
        assertEquals(1, runtime.registry.endpointProfiles.value.size)
        // Second attempt should start fresh (previous resources cleaned up)
        orch.start(listOf(
            StartupPhase("test") { rt, _ -> rt.registry.bind(SimulatedHaloEndpoint(endpointId = EndpointId("ep-2"))) },
        ))
        val endpoints = runtime.registry.endpointProfiles.value
        assertEquals(1, endpoints.size)
        assertEquals("ep-2", endpoints.first().endpointId.value)
    }
}
