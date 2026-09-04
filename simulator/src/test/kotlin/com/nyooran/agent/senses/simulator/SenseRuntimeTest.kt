package com.nyooran.agent.senses.simulator

import com.nyooran.agent.senses.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SenseRuntimeTest {

    @Test
    fun initialStateIsIdle() {
        val runtime = SenseRuntime()
        assertEquals(RuntimeState.Idle, runtime.state.value)
        assertFalse(runtime.isReady)
    }

    // ------------------------------------------------------------------ generation

    @Test
    fun startGenerationIncrementsGenerationId() {
        val runtime = SenseRuntime()
        val gen1 = runtime.startGeneration()
        val gen2 = runtime.startGeneration()
        assertTrue(gen2 > gen1)
        assertEquals(gen2, runtime.generation)
    }

    @Test
    fun startGenerationTransitionsToStarting() {
        val runtime = SenseRuntime()
        runtime.startGeneration()
        assertTrue(runtime.state.value is RuntimeState.Starting)
    }

    @Test
    fun markReadyTransitionsToReady() {
        val runtime = SenseRuntime()
        runtime.startGeneration()
        runtime.markReady()
        assertTrue(runtime.isReady)
        assertTrue(runtime.state.value is RuntimeState.Ready)
    }

    @Test
    fun markFailedTransitionsToFailed() {
        val runtime = SenseRuntime()
        runtime.startGeneration()
        runtime.markFailed("model load failed")
        val state = runtime.state.value
        assertTrue(state is RuntimeState.Failed)
        assertEquals("model load failed", state.error)
    }

    // ------------------------------------------------------------------ shutdown

    @Test
    fun shutdownTransitionsToStopped() = runTest {
        val runtime = SenseRuntime()
        runtime.startGeneration()
        runtime.markReady()
        runtime.shutdown()
        assertTrue(runtime.state.value is RuntimeState.Stopped)
        assertFalse(runtime.isReady)
    }

    @Test
    fun shutdownIsIdempotent() = runTest {
        val runtime = SenseRuntime()
        runtime.startGeneration()
        runtime.shutdown()
        runtime.shutdown()  // should not throw
        assertTrue(runtime.state.value is RuntimeState.Stopped)
    }

    @Test
    fun shutdownCancelsActiveOperations() = runTest {
        val runtime = SenseRuntime()
        runtime.startGeneration()
        runtime.markReady()
        val job = launch {
            delay(60_000)  // long-running operation
        }
        runtime.trackOperation("op-1", job)
        assertEquals(1, runtime.activeOperationCount)
        runtime.shutdown()
        advanceUntilIdle()
        assertTrue(job.isCancelled)
        assertEquals(0, runtime.activeOperationCount)
    }

    @Test
    fun shutdownUnbindsAllEndpoints() = runTest {
        val runtime = SenseRuntime()
        runtime.startGeneration()
        runtime.markReady()
        runtime.registry.bind(SimulatedHaloEndpoint())
        assertEquals(1, runtime.registry.endpointProfiles.value.size)
        runtime.shutdown()
        assertEquals(0, runtime.registry.endpointProfiles.value.size)
    }

    @Test
    fun shutdownClosesRegisteredCloseablesInLifoOrder() = runTest {
        val runtime = SenseRuntime()
        runtime.startGeneration()
        runtime.markReady()
        val closeOrder = mutableListOf<String>()
        runtime.registerCloseable(AutoCloseable { closeOrder.add("first") })
        runtime.registerCloseable(AutoCloseable { closeOrder.add("second") })
        runtime.registerCloseable(AutoCloseable { closeOrder.add("third") })
        runtime.shutdown()
        // LIFO: third, second, first
        assertEquals(listOf("third", "second", "first"), closeOrder)
    }

    @Test
    fun shutdownToleratesCloseableFailures() = runTest {
        val runtime = SenseRuntime()
        runtime.startGeneration()
        runtime.markReady()
        var secondClosed = false
        runtime.registerCloseable(AutoCloseable { error("close failed") })
        runtime.registerCloseable(AutoCloseable { secondClosed = true })
        runtime.shutdown()
        assertTrue(secondClosed)  // second closeable still closed despite first failing
    }

    // ------------------------------------------------------------------ reset

    @Test
    fun resetAllowsRestartAfterShutdown() = runTest {
        val runtime = SenseRuntime()
        runtime.startGeneration()
        runtime.markReady()
        runtime.shutdown()
        runtime.reset()
        assertEquals(RuntimeState.Idle, runtime.state.value)
        val gen = runtime.startGeneration()
        assertTrue(gen > 0)
    }

    // ------------------------------------------------------------------ operation tracking

    @Test
    fun cancelOperationCancelsSpecificJob() = runTest {
        val runtime = SenseRuntime()
        runtime.startGeneration()
        runtime.markReady()
        val job = launch { delay(60_000) }
        runtime.trackOperation("op-1", job)
        val cancelled = runtime.cancelOperation("op-1")
        assertTrue(cancelled)
        advanceUntilIdle()
        assertTrue(job.isCancelled)
    }

    @Test
    fun cancelOperationReturnsFalseForUnknownId() = runTest {
        val runtime = SenseRuntime()
        runtime.startGeneration()
        val cancelled = runtime.cancelOperation("nonexistent")
        assertFalse(cancelled)
    }

    @Test
    fun untrackOperationRemovesFromTracking() = runTest {
        val runtime = SenseRuntime()
        runtime.startGeneration()
        val job = launch { delay(60_000) }
        runtime.trackOperation("op-1", job)
        assertEquals(1, runtime.activeOperationCount)
        runtime.untrackOperation("op-1")
        assertEquals(0, runtime.activeOperationCount)
        job.cancel()
    }

    // ------------------------------------------------------------------ registry integration

    @Test
    fun runtimeRegistrySupportsEndpointResolution() = runTest {
        val runtime = SenseRuntime()
        runtime.startGeneration()
        runtime.markReady()
        runtime.registry.bind(SimulatedHaloEndpoint())
        val result = runtime.registry.resolve(SenseCapability.AudioInput)
        assertTrue(result is ResolveResult.Resolved)
        runtime.shutdown()
    }

    @Test
    fun multipleRuntimesCoexistWithoutInterference() = runTest {
        val runtime1 = SenseRuntime()
        val runtime2 = SenseRuntime()
        runtime1.startGeneration()
        runtime2.startGeneration()
        runtime1.registry.bind(SimulatedHaloEndpoint(endpointId = EndpointId("ep-1")))
        runtime2.registry.bind(SimulatedHaloEndpoint(endpointId = EndpointId("ep-2")))
        assertEquals(1, runtime1.registry.endpointProfiles.value.size)
        assertEquals(1, runtime2.registry.endpointProfiles.value.size)
        runtime1.shutdown()
        // runtime2 should be unaffected
        assertEquals(1, runtime2.registry.endpointProfiles.value.size)
        runtime2.shutdown()
    }
}
