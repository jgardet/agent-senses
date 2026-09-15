package com.nyooran.agent.senses.simulator

import com.nyooran.agent.senses.*
import halo.engine.SpritePacker
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SimulatedHaloEndpointTest {

    private fun makeEndpoint(
        scenario: SimulatedHaloEndpoint.HaloScenario = SimulatedHaloEndpoint.HaloScenario(),
    ) = SimulatedHaloEndpoint(scenario = scenario)

    // ------------------------------------------------------------------ profile

    @Test
    fun profileAdvertisesHaloCapabilities() {
        val ep = makeEndpoint()
        val caps = ep.profile.capabilities
        assertTrue(ep.profile.supports(SenseCapability.AudioInput))
        assertTrue(ep.profile.supports(SenseCapability.AudioOutput))
        assertTrue(ep.profile.supports(SenseCapability.ImageInput))
        assertTrue(ep.profile.supports(SenseCapability.VisualOutput))
        assertTrue(ep.profile.supports(SenseCapability.InteractionInput))
        assertTrue(ep.profile.supports(SenseCapability.StatusInput))
        // Halo does NOT support text input/output
        assertFalse(ep.profile.supports(SenseCapability.TextInput))
        assertFalse(ep.profile.supports(SenseCapability.TextOutput))
    }

    @Test
    fun profileBackendKindIsSimulator() {
        val ep = makeEndpoint()
        assertEquals(BackendKind.SIMULATOR, ep.profile.backendKind)
    }

    @Test
    fun profileStateIsDisconnectedBeforeConnect() {
        val ep = makeEndpoint()
        assertEquals(EndpointState.DISCONNECTED, ep.profile.state)
    }

    @Test
    fun profileHasDisplayName() {
        val ep = makeEndpoint(SimulatedHaloEndpoint.HaloScenario(displayName = "Test Halo"))
        assertEquals("Test Halo", ep.profile.displayName)
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

    @Test
    fun reconnectIsRepeatable() = runTest {
        val ep = makeEndpoint()
        ep.connect()
        assertEquals(EndpointState.READY, ep.state.value)
        ep.disconnect()
        assertEquals(EndpointState.DISCONNECTED, ep.state.value)
        ep.connect()
        assertEquals(EndpointState.READY, ep.state.value)
    }

    @Test
    fun disconnectClearsRecordedMedia() = runTest {
        val ep = makeEndpoint()
        ep.connect()
        ep.visualOutput(VisualOutputRequest(VisualContent(
            VisualContent.VisualKind.DEVICE_NATIVE,
            byteArrayOf(0x48, 0x52, 0x50, 0x31, 0, 0, 0),  // "HRP1" + reserved + 0 commands
        )))
        assertTrue(ep.recordedHrp.isNotEmpty())
        ep.disconnect()
        assertTrue(ep.recordedHrp.isEmpty())
        assertTrue(ep.recordedAudio.isEmpty())
        assertTrue(ep.recordedInteractions.isEmpty())
    }

    // ------------------------------------------------------------------ audio input

    @Test
    fun audioInputReturnsProvenanceWithFixtureId() = runTest {
        val ep = makeEndpoint(SimulatedHaloEndpoint.HaloScenario(
            fixtureId = "test-audio-fixture",
            audioFixture = ByteArray(320) { 0x42 },
            micChunkCount = 5,
        ))
        ep.connect()
        val result = ep.audioInput(AudioInputRequest(maxDurationMillis = 1000, maxBytes = 65536))
        assertEquals(SenseCapability.AudioInput, result.provenance.capability)
        assertEquals(BackendKind.SIMULATOR, result.provenance.backendKind)
        assertEquals("test-audio-fixture", result.provenance.fixtureId)
        assertEquals(ResultOrigin.MICROPHONE, result.provenance.origin)
        assertNotNull(result.provenance.operationId)
        assertTrue(result.audio.isNotEmpty())
    }

    @Test
    fun audioInputProvenanceHasMediaFormat() = runTest {
        val ep = makeEndpoint()
        ep.connect()
        val result = ep.audioInput(AudioInputRequest(maxDurationMillis = 100, maxBytes = 1024))
        val fmt = result.provenance.mediaFormat
        assertNotNull(fmt)
        assertEquals(16000, fmt.sampleRate)
        assertEquals(1, fmt.channels)
        assertNotNull(fmt.durationMillis)
    }

    // ------------------------------------------------------------------ audio output

    @Test
    fun audioOutputRecordsAudio() = runTest {
        val ep = makeEndpoint()
        ep.connect()
        val audio = ByteArray(100) { 0x55 }
        ep.audioOutput(AudioOutputRequest(
            audio = audio,
            format = AudioFormat(16000, 16, 1, "wav", "audio/wav"),
        ))
        assertEquals(1, ep.recordedAudio.size)
        assertEquals(100, ep.recordedAudio[0].size)
    }

    @Test
    fun audioOutputProvenanceIdentifiesSimulator() = runTest {
        val ep = makeEndpoint()
        ep.connect()
        val result = ep.audioOutput(AudioOutputRequest(
            audio = ByteArray(10),
            format = AudioFormat(16000, 16, 1, "wav", "audio/wav"),
        ))
        assertEquals(BackendKind.SIMULATOR, result.provenance.backendKind)
        assertEquals(ResultOrigin.SPEAKER, result.provenance.origin)
    }

    // ------------------------------------------------------------------ image input

    @Test
    fun imageInputReturnsFixtureImage() = runTest {
        val fixtureImage = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())  // minimal JPEG
        val ep = makeEndpoint(SimulatedHaloEndpoint.HaloScenario(
            imageFixture = fixtureImage,
            imageFormat = ImageFormat("jpeg", "image/jpeg", 640, 640),
        ))
        ep.connect()
        val result = ep.imageInput(ImageInputRequest(resolution = 640, maxBytes = 65536))
        assertEquals("jpeg", result.format.encoding)
        assertEquals(640, result.format.width)
        assertEquals("halo-default", result.provenance.fixtureId)
        assertEquals(ResultOrigin.CAMERA, result.provenance.origin)
    }

    // ------------------------------------------------------------------ visual output

    @Test
    fun visualOutputRecordsHrpPayload() = runTest {
        val ep = makeEndpoint()
        ep.connect()
        val hrp = byteArrayOf(0x48, 0x52, 0x50, 0x31, 0, 0, 0)  // "HRP1" + reserved + 0 commands
        ep.visualOutput(VisualOutputRequest(VisualContent(
            VisualContent.VisualKind.DEVICE_NATIVE, hrp,
        )))
        assertEquals(1, ep.recordedHrp.size)
        assertEquals(7, ep.recordedHrp[0].size)
    }

    @Test
    fun visualOutputSpriteWithoutPackerIsUnavailable() = runTest {
        val ep = makeEndpoint()
        ep.connect()
        val hsd = """{"scene":{"children":[{"type":"sprite","src":"data:image/png;base64,x","x":4,"y":4,"bpp":4,"resource_id":7}]}}"""
        assertFailsWith<SensesError.Unavailable> {
            ep.visualOutput(VisualOutputRequest(VisualContent(
                VisualContent.VisualKind.DEVICE_NATIVE, hsd.toByteArray(), "hsd",
            )))
        }
    }

    @Test
    fun visualOutputSpriteWithInjectedPacker() = runTest {
        val ep = makeEndpoint()
        ep.spritePacker = object : SpritePacker {
            override fun pack(src: String, width: Int?, height: Int?, bpp: Int) =
                SpritePacker.Sprite(
                    width = width ?: 8,
                    height = height ?: 8,
                    bpp = bpp,
                    numColors = 2,
                    paletteData = byteArrayOf(0, 0, 0, -1, -1, -1),
                    pixelData = ByteArray(64) { 1 },
                )
        }
        ep.connect()
        val hsd = """{"scene":{"children":[{"type":"sprite","src":"data:image/png;base64,x","x":4,"y":4,"bpp":4,"resource_id":7}]}}"""
        ep.visualOutput(VisualOutputRequest(VisualContent(
            VisualContent.VisualKind.DEVICE_NATIVE, hsd.toByteArray(), "hsd",
        )))
        assertTrue(ep.recordedHrp.isNotEmpty())
        // The sprite landed on the simulated framebuffer (pixels are ARGB;
        // the cleared background is opaque black).
        assertTrue(ep.framebufferSnapshot().any { it != 0xFF000000.toInt() })
    }

    @Test
    fun visualOutputTextRoutesToPlainText() = runTest {
        val ep = makeEndpoint()
        ep.connect()
        ep.visualOutput(VisualOutputRequest(VisualContent(
            VisualContent.VisualKind.TEXT, "Hello".toByteArray(),
        )))
        // Should not throw; text is handled via PLAIN_TEXT
    }

    // ------------------------------------------------------------------ interaction

    @Test
    fun interactionInputReturnsTapEvent() = runTest {
        val ep = makeEndpoint()
        ep.connect()
        ep.injectTap(TapGesture.SINGLE)
        val result = ep.interactionInput(InteractionInputRequest(
            acceptedGestures = setOf(InteractionType.TAP_SINGLE),
        ))
        assertTrue(result.event is InteractionEvent.Tap)
        assertEquals(TapGesture.SINGLE, (result.event as InteractionEvent.Tap).gesture)
        assertEquals(ResultOrigin.SENSOR, result.provenance.origin)
    }

    @Test
    fun injectTapRecordsInteraction() = runTest {
        val ep = makeEndpoint()
        ep.connect()
        ep.injectTap(TapGesture.DOUBLE)
        assertEquals(1, ep.recordedInteractions.size)
        val event = ep.recordedInteractions[0]
        assertTrue(event is InteractionEvent.Tap)
        assertEquals(TapGesture.DOUBLE, event.gesture)
    }

    @Test
    fun injectButtonRecordsInteraction() = runTest {
        val ep = makeEndpoint()
        ep.connect()
        ep.injectButton(ButtonGesture.LONG)
        assertEquals(1, ep.recordedInteractions.size)
        val event = ep.recordedInteractions[0]
        assertTrue(event is InteractionEvent.Button)
        assertEquals(ButtonGesture.LONG, event.gesture)
    }

    // ------------------------------------------------------------------ status

    @Test
    fun statusInputReturnsBatteryFromScenario() = runTest {
        val ep = makeEndpoint(SimulatedHaloEndpoint.HaloScenario(
            batteryLevel = 42,
            batteryVoltage = 3900,
            batteryCharging = true,
        ))
        ep.connect()
        val result = ep.statusInput(StatusInputRequest())
        assertEquals(42, result.status.batteryLevel)
        assertEquals(3900, result.status.batteryVoltage)
        assertEquals(true, result.status.batteryCharging)
        assertEquals(ResultOrigin.SENSOR, result.provenance.origin)
    }

    @Test
    fun statusProvenanceHasFixtureId() = runTest {
        val ep = makeEndpoint(SimulatedHaloEndpoint.HaloScenario(fixtureId = "battery-fixture"))
        ep.connect()
        val result = ep.statusInput(StatusInputRequest())
        assertEquals("battery-fixture", result.provenance.fixtureId)
    }

    // ------------------------------------------------------------------ unsupported

    @Test
    fun textInputThrowsUnavailable() = runTest {
        val ep = makeEndpoint()
        ep.connect()
        var threw = false
        try {
            ep.textInput(TextInputRequest())
        } catch (e: SensesError.Unavailable) {
            threw = true
        }
        assertTrue(threw)
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    fun concurrencyProfileHasAudioDomain() {
        val ep = makeEndpoint()
        val domains = ep.profile.concurrency.resourceDomains
        assertEquals("audio", domains[SenseCapability.AudioInput])
        assertEquals("audio", domains[SenseCapability.AudioOutput])
    }

    @Test
    fun concurrencyProfileHasAudioImageConflict() {
        val ep = makeEndpoint()
        assertFalse(ep.profile.canOverlap(SenseCapability.AudioInput, SenseCapability.ImageInput))
    }

    // ------------------------------------------------------------------ registry integration

    @Test
    fun endpointWorksInRegistry() = runTest {
        val registry = SenseEndpointRegistry()
        val ep = makeEndpoint()
        registry.bind(ep)
        val result = registry.resolve(SenseCapability.AudioInput)
        assertTrue(result is ResolveResult.Resolved)
        assertEquals(ep.profile.endpointId, result.endpoint.profile.endpointId)
        registry.unbindAll()
    }
}
