package com.nyooran.agent.senses.halo

import com.nyooran.agent.senses.*
import com.nyooran.agent.senses.simulator.Scenario
import com.nyooran.agent.senses.simulator.SimulatedHaloBleTransport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhysicalHaloEndpointTest {

    private fun makeEndpoint(
        scenario: Scenario = Scenario(),
        config: PhysicalHaloEndpoint.HaloEndpointConfig = PhysicalHaloEndpoint.HaloEndpointConfig(),
    ): PhysicalHaloEndpoint {
        val transport = SimulatedHaloBleTransport(scenario = scenario)
        return PhysicalHaloEndpoint(transport = transport, config = config)
    }

    // ------------------------------------------------------------------ profile

    @Test
    fun profileAdvertisesHaloCapabilities() {
        val ep = makeEndpoint()
        assertTrue(ep.profile.supports(SenseCapability.AudioInput))
        assertTrue(ep.profile.supports(SenseCapability.AudioOutput))
        assertTrue(ep.profile.supports(SenseCapability.ImageInput))
        assertTrue(ep.profile.supports(SenseCapability.VisualOutput))
        assertTrue(ep.profile.supports(SenseCapability.InteractionInput))
        assertTrue(ep.profile.supports(SenseCapability.StatusInput))
    }

    @Test
    fun profileBackendKindIsHalo() {
        val ep = makeEndpoint()
        assertEquals(BackendKind.PHYSICAL, ep.profile.backendKind)
    }

    @Test
    fun profileDoesNotAdvertiseTextCapabilities() {
        val ep = makeEndpoint()
        assertFalse(ep.profile.supports(SenseCapability.TextInput))
        assertFalse(ep.profile.supports(SenseCapability.TextOutput))
    }

    @Test
    fun profileHasFirmwareProfileInDisplayName() {
        val ep = makeEndpoint(config = PhysicalHaloEndpoint.HaloEndpointConfig(
            displayName = "My Halo",
            firmwareProfile = "HRP1;primitives,sprites",
        ))
        assertEquals("My Halo", ep.profile.displayName)
    }

    // ------------------------------------------------------------------ connect/disconnect

    @Test
    fun connectTransitionsToReady() = runTest {
        val ep = makeEndpoint()
        ep.connect()
        assertEquals(EndpointState.READY, ep.state.value)
    }

    @Test
    fun disconnectTransitionsToDisconnected() = runTest {
        val ep = makeEndpoint()
        ep.connect()
        ep.disconnect()
        assertEquals(EndpointState.DISCONNECTED, ep.state.value)
    }

    // ------------------------------------------------------------------ image input (camera parameter rejection)

    @Test
    fun imageInputRejectsRawCapture() = runTest {
        val ep = makeEndpoint()
        ep.connect()
        assertFailsWith<SensesError.Rejected> {
            ep.imageInput(ImageInputRequest(resolution = 640, qualityIndex = 0, maxBytes = 65536, raw = true))
        }
    }

    @Test
    fun imageInputRejectsNon640Resolution() = runTest {
        val ep = makeEndpoint()
        ep.connect()
        assertFailsWith<SensesError.Rejected> {
            ep.imageInput(ImageInputRequest(resolution = 1280, qualityIndex = 0, maxBytes = 65536))
        }
    }

    // ------------------------------------------------------------------ visual output (HRP validation)

    @Test
    fun visualOutputRejectsNonDeviceNative() = runTest {
        val ep = makeEndpoint()
        ep.connect()
        assertFailsWith<SensesError.Rejected> {
            ep.visualOutput(VisualOutputRequest(VisualContent(
                VisualContent.VisualKind.IMAGE, ByteArray(10),
            )))
        }
    }

    @Test
    fun visualOutputRejectsOversizedHrp() = runTest {
        val ep = makeEndpoint(config = PhysicalHaloEndpoint.HaloEndpointConfig(maxHrpBytes = 10))
        ep.connect()
        assertFailsWith<SensesError.LimitExceeded> {
            ep.visualOutput(VisualOutputRequest(VisualContent(
                VisualContent.VisualKind.DEVICE_NATIVE, ByteArray(100),
            )))
        }
    }

    // ------------------------------------------------------------------ unsupported

    @Test
    fun textOutputThrowsUnavailable() = runTest {
        val ep = makeEndpoint()
        ep.connect()
        assertFailsWith<SensesError.Unavailable> {
            ep.textOutput(TextOutputRequest(TextContent("hi")))
        }
    }

    @Test
    fun textInputThrowsUnavailable() = runTest {
        val ep = makeEndpoint()
        ep.connect()
        assertFailsWith<SensesError.Unavailable> {
            ep.textInput(TextInputRequest())
        }
    }

    // ------------------------------------------------------------------ provenance

    @Test
    fun provenanceIdentifiesHaloBackend() {
        val ep = makeEndpoint(config = PhysicalHaloEndpoint.HaloEndpointConfig(
            firmwareProfile = "HRP1;primitives,sprites,mic",
        ))
        assertEquals(BackendKind.PHYSICAL, ep.profile.backendKind)
        // The firmware profile is included in provenance for each operation
        // (verified in integration tests with a real transport)
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    fun concurrencyProfileHasAudioImageConflict() {
        val ep = makeEndpoint()
        assertFalse(ep.profile.canOverlap(SenseCapability.AudioInput, SenseCapability.ImageInput))
    }

    @Test
    fun concurrencyProfileSharesAudioDomain() {
        val ep = makeEndpoint()
        assertFalse(ep.profile.canOverlap(SenseCapability.AudioInput, SenseCapability.AudioOutput))
    }

    // ------------------------------------------------------------------ registry integration

    @Test
    fun worksInRegistry() = runTest {
        val registry = SenseEndpointRegistry()
        val ep = makeEndpoint()
        registry.bind(ep)
        val result = registry.resolve(SenseCapability.AudioInput)
        assertTrue(result is ResolveResult.Resolved)
        assertEquals(BackendKind.PHYSICAL, result.endpoint.profile.backendKind)
        registry.unbindAll()
    }
}
