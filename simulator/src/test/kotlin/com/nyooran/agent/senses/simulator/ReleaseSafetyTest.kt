package com.nyooran.agent.senses.simulator

import com.nyooran.agent.senses.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReleaseSafetyTest {

    // ------------------------------------------------------------------ ReleaseSafetyCheck

    @Test
    fun releaseSafetyCheckDoesNothingInDebug() {
        // isRelease=false should never throw, even if simulator is present
        ReleaseSafetyCheck.verify(isRelease = false)
    }

    @Test
    fun releaseSafetyCheckThrowsInReleaseWhenSimulatorPresent() {
        // In this test environment, the simulator IS on the classpath
        // (we're in the simulator module), so a release check should throw
        assertFailsWith<ReleaseSafetyException> {
            ReleaseSafetyCheck.verify(isRelease = true)
        }
    }

    @Test
    fun isSimulatorPresentReturnsTrueWhenSimulatorOnClasspath() {
        // We're in the simulator module, so it should be present
        assertTrue(ReleaseSafetyCheck.isSimulatorPresent())
    }

    @Test
    fun releaseSafetyExceptionMessageListsFoundClasses() {
        val e = assertFailsWith<ReleaseSafetyException> {
            ReleaseSafetyCheck.verify(isRelease = true)
        }
        assertTrue(e.message!!.contains("Simulator classes found"))
        assertTrue(e.message!!.contains("SimulatedHaloEndpoint"))
    }

    // ------------------------------------------------------------------ EndpointBinder

    @Test
    fun endpointBinderBindsEndpointExplicitly() = runTest {
        val runtime = SenseRuntime()
        runtime.startGeneration()
        val binder = EndpointBinder(runtime)
        binder.bind(SimulatedHaloEndpoint(endpointId = EndpointId("halo-1")))
        assertEquals(1, binder.boundEndpoints().size)
        assertEquals("halo-1", binder.boundEndpoints().first().endpointId.value)
        runtime.shutdown()
    }

    @Test
    fun endpointBinderUnbindsEndpoint() = runTest {
        val runtime = SenseRuntime()
        runtime.startGeneration()
        val binder = EndpointBinder(runtime)
        binder.bind(SimulatedHaloEndpoint(endpointId = EndpointId("halo-1")))
        assertEquals(1, binder.boundEndpoints().size)
        binder.unbind(EndpointId("halo-1"))
        assertEquals(0, binder.boundEndpoints().size)
        runtime.shutdown()
    }

    @Test
    fun endpointBinderListsBoundEndpoints() = runTest {
        val runtime = SenseRuntime()
        runtime.startGeneration()
        val binder = EndpointBinder(runtime)
        binder.bind(SimulatedHaloEndpoint(endpointId = EndpointId("halo-1")))
        binder.bind(SimulatedHaloEndpoint(endpointId = EndpointId("halo-2")))
        val ids = binder.boundEndpoints().map { it.endpointId.value }.sorted()
        assertEquals(listOf("halo-1", "halo-2"), ids)
        runtime.shutdown()
    }

    @Test
    fun endpointBinderVerifyReleaseSafeDoesNothingInDebug() = runTest {
        val runtime = SenseRuntime()
        runtime.startGeneration()
        val binder = EndpointBinder(runtime)
        binder.bind(SimulatedHaloEndpoint())
        // Should not throw in debug
        binder.verifyReleaseSafe(isRelease = false)
        runtime.shutdown()
    }

    @Test
    fun endpointBinderVerifyReleaseSafeThrowsInReleaseWithSimulator() = runTest {
        val runtime = SenseRuntime()
        runtime.startGeneration()
        val binder = EndpointBinder(runtime)
        binder.bind(SimulatedHaloEndpoint())
        // Should throw in release because a SIMULATOR backend is bound
        assertFailsWith<ReleaseSafetyException> {
            binder.verifyReleaseSafe(isRelease = true)
        }
        runtime.shutdown()
    }

    @Test
    fun endpointBinderVerifyReleaseSafePassesWithPhysicalEndpoint() = runTest {
        val runtime = SenseRuntime()
        runtime.startGeneration()
        val binder = EndpointBinder(runtime)
        // Bind a non-simulator endpoint (use a mock with PHYSICAL backend)
        binder.bind(TestPhysicalEndpoint())
        // Should not throw in release
        binder.verifyReleaseSafe(isRelease = true)
        runtime.shutdown()
    }

    // ------------------------------------------------------------------ test helpers

    /** A minimal endpoint with PHYSICAL backend for release-safety tests. */
    private class TestPhysicalEndpoint : SenseEndpoint {
        override val profile = SenseProfile(
            endpointId = EndpointId("physical-1"),
            backendName = "TestPhysical",
            backendKind = BackendKind.PHYSICAL,
            state = EndpointState.READY,
            capabilities = setOf(SenseCapability.StatusInput),
            limits = emptyMap(),
            concurrency = ConcurrencyProfile(),
        )

        override suspend fun connect() {}
        override suspend fun disconnect() {}
        override suspend fun audioInput(request: AudioInputRequest) = throw SensesError.Unavailable("not supported")
        override suspend fun audioOutput(request: AudioOutputRequest) = throw SensesError.Unavailable("not supported")
        override suspend fun imageInput(request: ImageInputRequest) = throw SensesError.Unavailable("not supported")
        override suspend fun visualOutput(request: VisualOutputRequest) = throw SensesError.Unavailable("not supported")
        override suspend fun textOutput(request: TextOutputRequest) = throw SensesError.Unavailable("not supported")
        override suspend fun textInput(request: TextInputRequest) = throw SensesError.Unavailable("not supported")
        override suspend fun interactionInput(request: InteractionInputRequest) = throw SensesError.Unavailable("not supported")
        override suspend fun statusInput(request: StatusInputRequest) = StatusInputResult(
            status = EndpointStatus(),
            provenance = Provenance(
                operationId = "test",
                endpointId = EndpointId("physical-1"),
                backendName = "TestPhysical",
                backendKind = BackendKind.PHYSICAL,
                capability = SenseCapability.StatusInput,
                origin = ResultOrigin.SENSOR,
                startedAt = 0,
                completedAt = 0,
            ),
        )
    }
}
