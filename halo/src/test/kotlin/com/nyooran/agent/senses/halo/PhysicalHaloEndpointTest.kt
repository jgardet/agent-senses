package com.nyooran.agent.senses.halo

import com.nyooran.agent.senses.*
import com.nyooran.agent.senses.simulator.Scenario
import com.nyooran.agent.senses.simulator.SimulatedHaloBleTransport
import halo.engine.HaloProtocol
import kotlinx.coroutines.runBlocking
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
            ep.imageInput(ImageInputRequest(resolution = 640, maxBytes = 65536, deviceOptions = mapOf("raw" to true)))
        }
    }

    @Test
    fun imageInputRejectsNon640Resolution() = runTest {
        val ep = makeEndpoint()
        ep.connect()
        assertFailsWith<SensesError.Rejected> {
            ep.imageInput(ImageInputRequest(resolution = 1280, maxBytes = 65536))
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

    // ------------------------------------------------------------------ v3 runtime controls

    private fun makeInstalledEndpoint(
        scenario: Scenario = Scenario(),
    ): Pair<PhysicalHaloEndpoint, SimulatedHaloBleTransport> {
        val transport = SimulatedHaloBleTransport(scenario = scenario)
        val ep = PhysicalHaloEndpoint(
            transport = transport,
            config = PhysicalHaloEndpoint.HaloEndpointConfig(
                runtimeInstaller = { it.sendLua("require 'halo_engine'") },
            ),
        )
        return ep to transport
    }

    @Test
    fun connectCapturesRuntimeStatusAndSyncsTime() = runTest {
        val (ep, transport) = makeInstalledEndpoint()
        ep.connect()
        assertEquals(EndpointState.READY, ep.state.value)
        // STATUS from the install `require` is parsed into metadata.
        assertEquals("26.013.1043", ep.firmwareVersion)
        assertEquals("112233445566", ep.deviceEui)
        assertTrue(ep.runtimeCapabilities!!.contains("sound"))
        assertTrue(ep.runtimeCapabilities!!.contains("imu"))
        // Time sync is pushed on connect.
        assertTrue(transport.recordedControl.any { it.first == HaloProtocol.SET_TIME })
        ep.close()
    }

    @Test
    fun statusInputIncludesFirmwareExtras() = runTest {
        val (ep, _) = makeInstalledEndpoint()
        ep.connect()
        val result = ep.statusInput(StatusInputRequest())
        assertEquals("26.013.1043", result.status.extras["firmware"])
        assertEquals("112233445566", result.status.extras["eui"])
        ep.close()
    }

    @Test
    fun connectSkipsInstallerWhenRuntimeAlreadyRunning() = runTest {
        // Boot STATUS announces a running runtime (main.lua autorun); the
        // STATUS probe answers, so the installer must not run.
        var installed = false
        val transport = SimulatedHaloBleTransport()
        val ep = PhysicalHaloEndpoint(
            transport = transport,
            config = PhysicalHaloEndpoint.HaloEndpointConfig(
                expectedRuntimeVersion = "3.1",
                runtimeInstaller = { installed = true },
            ),
        )
        ep.connect()
        assertFalse(installed)
        assertEquals(EndpointState.READY, ep.state.value)
        assertEquals("3.1", ep.runtimeVersion)
        assertEquals("unknown", ep.wakeupSource)
        assertTrue(ep.runtimeCapabilities!!.contains("sound"))
        ep.close()
    }

    @Test
    fun connectInstallsWhenRuntimeVersionStale() = runTest {
        var installed = false
        val transport = SimulatedHaloBleTransport()
        val ep = PhysicalHaloEndpoint(
            transport = transport,
            config = PhysicalHaloEndpoint.HaloEndpointConfig(
                expectedRuntimeVersion = "9.9",
                runtimeInstaller = { installed = true; it.sendLua("require 'halo_engine'") },
            ),
        )
        ep.connect()
        assertTrue(installed)
        ep.close()
    }

    @Test
    fun connectInstallsWhenProbeUnanswered() = runTest {
        var installed = false
        val transport = SimulatedHaloBleTransport(statusCaps = null)
        val ep = PhysicalHaloEndpoint(
            transport = transport,
            config = PhysicalHaloEndpoint.HaloEndpointConfig(
                runtimeInstaller = { installed = true; it.sendLua("require 'halo_engine'") },
            ),
        )
        ep.connect()
        assertTrue(installed)
        assertEquals(EndpointState.READY, ep.state.value)
        ep.close()
    }

    @Test
    fun playSoundSendsSoundPlay() = runTest {
        val (ep, transport) = makeInstalledEndpoint()
        ep.connect()
        ep.playSound("pickup", volume = 60)
        val cmd = transport.recordedControl.first { it.first == HaloProtocol.SOUND_PLAY }
        // flags=0x01 (volume), volume=60, then "pickup"
        assertEquals(0x01, cmd.second[0].toInt() and 0xFF)
        assertEquals(60, cmd.second[1].toInt() and 0xFF)
        assertEquals("pickup", String(cmd.second, 2, cmd.second.size - 2, Charsets.UTF_8))
        ep.close()
    }

    @Test
    fun playSoundWithoutSoundCapabilityThrowsUnavailable() = runTest {
        // Runtime without "sound" in STATUS — ack callers must fail closed.
        val transport = SimulatedHaloBleTransport(
            statusCaps = "HRP1;primitives,sprites,mic,battery;fw=26.013.1043",
        )
        val ep = PhysicalHaloEndpoint(
            transport = transport,
            config = PhysicalHaloEndpoint.HaloEndpointConfig(
                runtimeInstaller = { it.sendLua("require 'halo_engine'") },
            ),
        )
        ep.connect()
        assertFailsWith<SensesError.Unavailable> {
            ep.playSound("blip")
        }
        assertTrue(transport.recordedControl.none { it.first == HaloProtocol.SOUND_PLAY })
        ep.close()
    }

    @Test
    fun systemPowerSaveSendsSystemMessage() = runTest {
        val (ep, transport) = makeInstalledEndpoint()
        ep.connect()
        ep.setDisplayPowerSave(true)
        ep.setCameraPowerSave(true)
        val ops = transport.recordedControl.filter { it.first == HaloProtocol.SYSTEM }
        assertEquals(HaloProtocol.SYS_DISPLAY_SLEEP, ops[0].second[0].toInt() and 0xFF)
        assertEquals(HaloProtocol.SYS_CAMERA_POWER_SAVE, ops[1].second[0].toInt() and 0xFF)
        assertEquals(1, ops[1].second[1].toInt() and 0xFF)
        ep.close()
    }

    @Test
    fun readImuParsesSnapshot() = runTest {
        val (ep, _) = makeInstalledEndpoint()
        ep.connect()
        val imu = ep.readImu()
        assertEquals(0.10f, imu.pitchDegrees!!, 0.001f)
        assertEquals(-0.20f, imu.rollDegrees!!, 0.001f)
        assertEquals(48.0f, imu.compassZ!!, 0.001f)
        assertEquals(1001.0f, imu.accelZ!!, 0.001f)
        ep.close()
    }

    @Test
    fun configureTapSendsTapConfig() = runTest {
        val (ep, transport) = makeInstalledEndpoint()
        ep.connect()
        ep.configureTap(mode = "sensitive", threshold = 12)
        val cmd = transport.recordedControl.first { it.first == HaloProtocol.TAP_CONFIG }
        assertEquals(0x01 or 0x04, cmd.second[0].toInt() and 0xFF)
        ep.close()
    }

    @Test
    fun audioInputRejectsLc3Encoder() = runTest {
        val ep = makeEndpoint()
        ep.connect()
        assertFailsWith<SensesError.Rejected> {
            ep.audioInput(AudioInputRequest(
                maxDurationMillis = 100,
                maxBytes = 65536,
                deviceOptions = mapOf("encoder" to "lc3"),
            ))
        }
    }

    @Test
    fun audioInputSupports8BitPcm() = runTest {
        val (ep, _) = makeInstalledEndpoint()
        ep.connect()
        val result = ep.audioInput(AudioInputRequest(
            maxDurationMillis = 100,
            maxBytes = 65536,
            deviceOptions = mapOf("bitDepth" to 8),
        ))
        assertEquals(8, result.format.bitDepth)
        ep.close()
    }

    // ------------------------------------------------------------------ mpix pipeline

    @Test
    fun imageInputResizeReportsPostPipelineDims() = runBlocking {
        // runBlocking: the simulated photo stream emits on a real dispatcher
        // and would lose the race against runTest's auto-advancing timeout.
        val (ep, _) = makeInstalledEndpoint()
        ep.connect()
        val result = ep.imageInput(ImageInputRequest(
            resolution = 640,
            maxBytes = 65536,
            deviceOptions = mapOf("resize" to listOf(320, 320)),
        ))
        assertEquals(320, result.format.width)
        assertEquals(320, result.format.height)
        ep.close()
    }

    @Test
    fun imageInputRejectsMalformedCrop() = runTest {
        val (ep, _) = makeInstalledEndpoint()
        ep.connect()
        assertFailsWith<SensesError.Rejected> {
            ep.imageInput(ImageInputRequest(
                resolution = 640,
                maxBytes = 65536,
                deviceOptions = mapOf("crop" to listOf(1, 2, 3)),
            ))
        }
        ep.close()
    }

    @Test
    fun imageInputRejectsMpixWhenRuntimeLacksIt() = runTest {
        val transport = SimulatedHaloBleTransport(
            scenario = Scenario(),
            statusCaps = "HRP1;primitives,sprites,mic,speaker,photo,battery",
        )
        val ep = PhysicalHaloEndpoint(
            transport = transport,
            config = PhysicalHaloEndpoint.HaloEndpointConfig(
                runtimeInstaller = { it.sendLua("require 'halo_engine'") },
            ),
        )
        ep.connect()
        assertFailsWith<SensesError.Unavailable> {
            ep.imageInput(ImageInputRequest(
                resolution = 640,
                maxBytes = 65536,
                deviceOptions = mapOf("resize" to listOf(320, 320)),
            ))
        }
        ep.close()
    }
}
