package com.nyooran.agent.senses.halo

import com.nyooran.agent.senses.*
import com.nyooran.agent.senses.simulator.Scenario
import com.nyooran.agent.senses.simulator.SimulatedHaloBleTransport
import halo.engine.HaloProtocol
import halo.engine.SpritePacker
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
                expectedRuntimeVersion = "3.2",
                runtimeInstaller = { installed = true },
            ),
        )
        ep.connect()
        assertFalse(installed)
        assertEquals(EndpointState.READY, ep.state.value)
        assertEquals("3.2", ep.runtimeVersion)
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

    @Test
    fun audioInputStopsEarlyOnTrailingSilence() = runBlocking {
        // runBlocking: the simulated mic stream emits on a real dispatcher
        // and would lose the race against runTest's virtual time.
        // 640-byte chunks = 20 ms each at 16 kHz s16le mono. High bytes set
        // to 0x04 give a 1024 peak amplitude, above the silence threshold.
        val loud = ByteArray(640) { i -> if (i % 2 == 1) 0x04 else 0x00 }
        val silent = ByteArray(640)
        var emitted = 0
        val transport = SimulatedHaloBleTransport(
            scenario = Scenario(),
            microphoneSource = { emit ->
                repeat(4) { emit(loud); emitted++ }
                while (true) {
                    emit(silent)
                    emitted++
                    kotlinx.coroutines.yield()
                }
            },
        )
        val ep = PhysicalHaloEndpoint(
            transport = transport,
            config = PhysicalHaloEndpoint.HaloEndpointConfig(
                silenceMinCaptureMillis = 40,
                silenceTrailingMillis = 100,
                silencePeakThreshold = 400,
            ),
        )
        ep.connect()
        val result = ep.audioInput(AudioInputRequest(
            maxDurationMillis = 10_000,
            maxBytes = 65536,
        ))
        // MICROPHONE_STOP cancelled the source after ~9 chunks (4 loud + ~5
        // silent); without early stop it would stream for the full 10 s.
        assertTrue(emitted < 50, "expected early stop, emitted=$emitted")
        assertTrue(result.durationMillis < 1_000)
        ep.close()
    }

    @Test
    fun audioInputSilenceEarlyStopDisabledCapturesFixture() = runTest {
        val ep = PhysicalHaloEndpoint(
            transport = SimulatedHaloBleTransport(scenario = Scenario()),
            config = PhysicalHaloEndpoint.HaloEndpointConfig(silenceEarlyStop = false),
        )
        ep.connect()
        val result = ep.audioInput(AudioInputRequest(maxDurationMillis = 100, maxBytes = 65536))
        assertTrue(result.audio.isNotEmpty())
        ep.close()
    }

    @Test
    fun audioOutputCancellationSendsSpeakerStop() = runBlocking {
        // runBlocking: playback is receiver-paced with real delays, so a
        // virtual-time dispatcher would let the whole clip finish before
        // the cancel lands. 1 s of PCM = 100 x 10 ms frames; cancelling
        // after 200 ms interrupts mid-stream.
        var speakerStarted = false
        var speakerStopped = false
        val transport = SimulatedHaloBleTransport(
            scenario = Scenario(),
            speakerSessionStart = { speakerStarted = true },
            speakerSessionEnd = { speakerStopped = true },
        )
        val ep = PhysicalHaloEndpoint(transport = transport)
        ep.connect()

        val pcm = ByteArray(32_000) // 1 s at 16 kHz s16le mono
        val job = launch {
            runCatching {
                ep.audioOutput(AudioOutputRequest(
                    audio = pcm,
                    format = AudioFormat(
                        encoding = "pcm",
                        mime = "audio/pcm",
                        sampleRate = 16_000,
                        bitDepth = 16,
                        channels = 1,
                    ),
                ))
            }
        }
        // Wait until playback has actually started, then barge in.
        val deadline = System.currentTimeMillis() + 5_000
        while (!speakerStarted && System.currentTimeMillis() < deadline) {
            delay(10)
        }
        assertTrue(speakerStarted, "playback never started")
        delay(200)
        job.cancel()
        job.join()

        assertTrue(speakerStopped, "cancelled playback must send SPEAKER_STOP")
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

    @Test
    fun imageInputAppliesDefaultJpegQualityWhenRuntimeHasMpix() = runBlocking {
        // runBlocking: the simulated photo stream emits on a real dispatcher.
        val (ep, transport) = makeInstalledEndpoint()
        ep.connect()
        ep.imageInput(ImageInputRequest(resolution = 640, maxBytes = 65536))
        val req = transport.lastCaptureRequest!!
        // 6-byte header + op count 1 + jpeg_quality op (0x07, 70).
        assertEquals(9, req.size)
        assertEquals(1, req[6].toInt())
        assertEquals(0x07, req[7].toInt() and 0xFF)
        assertEquals(70, req[8].toInt() and 0xFF)
        ep.close()
    }

    @Test
    fun imageInputSkipsDefaultJpegQualityWhenOpsProvided() = runBlocking {
        val (ep, transport) = makeInstalledEndpoint()
        ep.connect()
        ep.imageInput(ImageInputRequest(
            resolution = 640,
            maxBytes = 65536,
            deviceOptions = mapOf("resize" to listOf(320, 320)),
        ))
        val req = transport.lastCaptureRequest!!
        // Only the caller's resize op — no default jpeg_quality appended.
        assertEquals(1, req[6].toInt())
        assertEquals(0x02, req[7].toInt() and 0xFF)
        ep.close()
    }

    @Test
    fun imageInputSkipsDefaultJpegQualityWithoutMpixCapability() = runBlocking {
        val transport = SimulatedHaloBleTransport(
            statusCaps = "HRP1;primitives,sprites,mic,battery;rt=3.1",
        )
        val ep = PhysicalHaloEndpoint(
            transport = transport,
            config = PhysicalHaloEndpoint.HaloEndpointConfig(
                runtimeInstaller = { it.sendLua("require 'halo_engine'") },
            ),
        )
        ep.connect()
        ep.imageInput(ImageInputRequest(resolution = 640, maxBytes = 65536))
        // No mpix ops tail on a capless runtime — legacy 6-byte header only.
        assertEquals(6, transport.lastCaptureRequest!!.size)
        ep.close()
    }

    // ------------------------------------------------------------------ sprite file cache

    private object TinyPacker : SpritePacker {
        override fun pack(src: String, width: Int?, height: Int?, bpp: Int) = SpritePacker.Sprite(
            width = 2, height = 2, bpp = 1, numColors = 2,
            paletteData = byteArrayOf(0, 0, 0, -1, -1, -1),
            pixelData = byteArrayOf(1, 1, 1, 1),
        )
    }

    private fun hrpOpcodes(frame: ByteArray): List<Int> {
        val opcodes = mutableListOf<Int>()
        var offset = 7
        while (offset < frame.size) {
            opcodes += frame[offset].toInt() and 0xFF
            offset += 3 + (((frame[offset + 1].toInt() and 0xFF) shl 8) or (frame[offset + 2].toInt() and 0xFF))
        }
        return opcodes
    }

    private fun spriteHsd(extra: String = ""): VisualOutputRequest = VisualOutputRequest(
        VisualContent(
            VisualContent.VisualKind.DEVICE_NATIVE,
            """{"scene":{"children":[{"type":"sprite","src":"mem://icon",$extra"x":4,"y":4,"w":2,"h":2,"bpp":1}]}}"""
                .toByteArray(),
            format = "hsd",
        ),
    )

    @Test
    fun spriteCachingStoresAssetThenEmitsCachedDefines() = runTest {
        val transport = SimulatedHaloBleTransport()
        val ep = PhysicalHaloEndpoint(
            transport = transport,
            config = PhysicalHaloEndpoint.HaloEndpointConfig(spritePacker = TinyPacker),
        )
        ep.connect()

        ep.visualOutput(spriteHsd())
        // First present persists the packed asset under a content-hash key.
        assertEquals(1, transport.spriteFiles.size)
        assertTrue(transport.spriteFiles.keys.single().matches(Regex("[A-Za-z0-9_-]{1,64}")))
        // The frame itself already uses the cached define — the endpoint
        // stores before sending, so opcode 0x10 resolves on-device.
        val firstOpcodes = hrpOpcodes(ep.recordedHrp.last())
        assertTrue(0x10 in firstOpcodes)
        assertFalse(0x0A in firstOpcodes)
        val stores = transport.recordedControl.count { it.first == HaloProtocol.SPRITE_STORE }
        assertEquals(1, stores)

        // Second present skips the store (session-tracked) but still draws.
        ep.visualOutput(spriteHsd())
        assertEquals(1, transport.recordedControl.count { it.first == HaloProtocol.SPRITE_STORE })
        assertTrue(0x10 in hrpOpcodes(ep.recordedHrp.last()))
        ep.close()
    }

    @Test
    fun spriteCachingDisabledWithoutRuntimeCapability() = runTest {
        // Older runtimes lack `spritecache`: sprites fall back to inline defines.
        val transport = SimulatedHaloBleTransport(
            statusCaps = "HRP1;primitives,sprites,mic,battery,lz4;rt=3.1",
        )
        val ep = PhysicalHaloEndpoint(
            transport = transport,
            config = PhysicalHaloEndpoint.HaloEndpointConfig(spritePacker = TinyPacker),
        )
        ep.connect()
        ep.visualOutput(spriteHsd())
        assertTrue(transport.spriteFiles.isEmpty())
        assertTrue(transport.recordedControl.none { it.first == HaloProtocol.SPRITE_STORE })
        assertTrue(0x0A in hrpOpcodes(ep.recordedHrp.last()))
        ep.close()
    }

    @Test
    fun precacheSpriteStoresAssetForExplicitCacheKey() = runTest {
        val transport = SimulatedHaloBleTransport()
        val epWithPacker = PhysicalHaloEndpoint(
            transport = transport,
            config = PhysicalHaloEndpoint.HaloEndpointConfig(spritePacker = TinyPacker),
        )
        epWithPacker.connect()
        val asset = halo.engine.HaloHost.packSpriteAsset(TinyPacker.pack("mem://icon", 2, 2, 1))
        epWithPacker.precacheSprite("icon_nav", asset)
        assertTrue(transport.spriteFiles.containsKey("icon_nav"))
        // Explicit cache_key HSD now resolves on both device and host mirror.
        epWithPacker.visualOutput(spriteHsd(""""cache_key":"icon_nav","""))
        assertTrue(0x10 in hrpOpcodes(epWithPacker.recordedHrp.last()))
        epWithPacker.close()
    }

    // ------------------------------------------------------------------ interaction broadcast (P3-03)
    //
    // runBlocking throughout: device messages are dispatched on the
    // endpoint's Dispatchers.IO message loop, not a virtual-time dispatcher.

    @Test
    fun tapReachesWaiterDiagnosticsAndCallback() = runBlocking {
        val observed = mutableListOf<InteractionEvent>()
        val transport = SimulatedHaloBleTransport(scenario = Scenario())
        val ep = PhysicalHaloEndpoint(
            transport = transport,
            config = PhysicalHaloEndpoint.HaloEndpointConfig(
                onInteraction = { observed.add(it) },
            ),
        )
        ep.connect()

        val waiter = async { ep.interactionInput(InteractionInputRequest()) }
        // Emit until the waiter has subscribed and caught one — there is no
        // subscription signal, so retry rather than race it.
        val deadline = System.currentTimeMillis() + 5_000
        while (!waiter.isCompleted && System.currentTimeMillis() < deadline) {
            transport.injectTap(1)
            delay(20)
        }
        val result = withTimeout(5_000) { waiter.await() }

        val tap = result.event as InteractionEvent.Tap
        assertEquals(TapGesture.SINGLE, tap.gesture)
        assertEquals(BackendKind.PHYSICAL, result.provenance.backendKind)
        assertEquals(ResultOrigin.SENSOR, result.provenance.origin)
        assertEquals(SenseCapability.InteractionInput, result.provenance.capability)

        // Broadcast is non-destructive: the diagnostics callback and the
        // recorded history observe the same event the waiter consumed.
        val diagDeadline = System.currentTimeMillis() + 2_000
        while (observed.isEmpty() && System.currentTimeMillis() < diagDeadline) delay(10)
        assertTrue(observed.any { it is InteractionEvent.Tap })
        assertTrue(ep.recordedInteractions.any { it is InteractionEvent.Tap })
        ep.close()
    }

    @Test
    fun everyWaiterReceivesTheSameEvent() = runBlocking {
        val (ep, transport) = makeInstalledEndpoint()
        ep.connect()
        val w1 = async { ep.interactionInput(InteractionInputRequest()) }
        val w2 = async { ep.interactionInput(InteractionInputRequest()) }
        val deadline = System.currentTimeMillis() + 5_000
        while (!(w1.isCompleted && w2.isCompleted) && System.currentTimeMillis() < deadline) {
            transport.injectTap(1)
            delay(20)
        }
        assertTrue(w1.isCompleted && w2.isCompleted,
            "broadcast must deliver the event to both waiters")
        assertTrue(withTimeout(5_000) { w1.await() }.event is InteractionEvent.Tap)
        assertTrue(withTimeout(5_000) { w2.await() }.event is InteractionEvent.Tap)
        ep.close()
    }

    @Test
    fun gestureFilterDeliversOnlyMatchingEvents() = runBlocking {
        val (ep, transport) = makeInstalledEndpoint()
        ep.connect()
        val waiter = async {
            ep.interactionInput(InteractionInputRequest(
                acceptedGestures = setOf(InteractionType.BUTTON_LONG),
            ))
        }
        // Taps must not satisfy a button-only waiter; they still reach
        // diagnostics rather than being consumed by the wrong subscriber.
        val deadline = System.currentTimeMillis() + 5_000
        while (!waiter.isCompleted && System.currentTimeMillis() < deadline) {
            transport.injectTap(1)
            transport.injectButton(3)
            delay(20)
        }
        val result = withTimeout(5_000) { waiter.await() }
        assertEquals(ButtonGesture.LONG, (result.event as InteractionEvent.Button).gesture)
        assertTrue(ep.recordedInteractions.any { it is InteractionEvent.Tap })
        ep.close()
    }

    @Test
    fun deviceDisconnectEmitsDisconnectedToWaiters() = runBlocking {
        val (ep, transport) = makeInstalledEndpoint()
        ep.connect()
        val waiter = async {
            ep.interactionInput(InteractionInputRequest(
                acceptedGestures = setOf(InteractionType.TAP_SINGLE),
            ))
        }
        delay(50) // let the waiter subscribe
        transport.simulateDisconnect()
        val result = withTimeout(5_000) { waiter.await() }
        // A filtered waiter still learns about the disconnect instead of
        // hanging until its timeout.
        assertEquals(InteractionEvent.Disconnected, result.event)
        assertEquals(EndpointState.DISCONNECTED, ep.state.value)
        ep.close()
    }

    @Test
    fun userDisconnectUnblocksWaiters() = runBlocking {
        val (ep, _) = makeInstalledEndpoint()
        ep.connect()
        val waiter = async { ep.interactionInput(InteractionInputRequest()) }
        delay(50)
        ep.disconnect()
        val result = withTimeout(5_000) { waiter.await() }
        assertEquals(InteractionEvent.Disconnected, result.event)
        ep.close()
    }

    @Test
    fun throwingInteractionCallbackDoesNotKillMessageLoop() = runBlocking {
        val transport = SimulatedHaloBleTransport(scenario = Scenario())
        val ep = PhysicalHaloEndpoint(
            transport = transport,
            config = PhysicalHaloEndpoint.HaloEndpointConfig(
                onInteraction = { throw RuntimeException("listener crashed") },
            ),
        )
        ep.connect()
        val waiter = async { ep.interactionInput(InteractionInputRequest()) }
        val deadline = System.currentTimeMillis() + 5_000
        while (!waiter.isCompleted && System.currentTimeMillis() < deadline) {
            transport.injectTap(1)
            transport.injectButton(1)
            delay(20)
        }
        val result = withTimeout(5_000) { waiter.await() }
        assertTrue(
            result.event is InteractionEvent.Tap || result.event is InteractionEvent.Button,
            "expected a delivered gesture, got ${result.event}",
        )
        assertEquals(EndpointState.READY, ep.state.value)
        ep.close()
    }
}
