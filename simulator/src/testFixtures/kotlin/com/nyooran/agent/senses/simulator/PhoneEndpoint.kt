package com.nyooran.agent.senses.simulator

import com.nyooran.agent.senses.*
import com.nyooran.agent.senses.audio.PcmToWav
import kotlinx.coroutines.delay

/**
 * Phase 3 P3-02: Phone sense endpoint.
 *
 * A sense endpoint backed by phone-local hardware: microphone, camera,
 * loudspeaker, screen, and battery. On a real device, the methods delegate
 * to Android APIs (AudioRecord, CameraX, AudioTrack, TextToSpeech, etc.).
 *
 * In the simulator/JVM module, this class provides a deterministic stub
 * that can be used for orchestration and route tests. The real Android
 * implementation lives in the app and wraps these same methods with
 * permission-aware hardware calls.
 *
 * Key properties:
 * - Permission-aware: checks permissions before capture (throws if missing).
 * - Camera: normalizes orientation, center-crops, encodes to 640x640.
 * - TTS: deterministic init, utterance tracking, cancellation, cleanup.
 * - Battery: labeled as phone state, not Halo state.
 * - Does NOT advertise Halo-specific capabilities (HRP display, wearable tap).
 * - Microphone PCM is wrapped as 16 kHz mono 16-bit WAV before returning.
 *
 * Acceptance: Without glasses, a real voice/image request can produce
 * audible and visible output through phone hardware.
 */
class PhoneEndpoint(
    private val config: PhoneConfig = PhoneConfig(),
) : SenseEndpoint {

    data class PhoneConfig(
        val endpointId: EndpointId = EndpointId("phone-1"),
        val displayName: String = "Phone",
        val hasMicrophone: Boolean = true,
        val hasCamera: Boolean = true,
        val hasSpeaker: Boolean = true,
        val hasScreen: Boolean = true,
        val hasBattery: Boolean = true,
        val hasKeyboard: Boolean = true,
        val audioFixture: ByteArray = ByteArray(320) { 0 },  // 10ms 16kHz mono 16-bit PCM
        val audioFormat: AudioFormat = AudioFormat(16000, 16, 1, "wav", "audio/wav"),
        val imageFixture: ByteArray = ByteArray(0),
        val imageFormat: ImageFormat = ImageFormat("jpeg", "image/jpeg", 640, 640),
        val batteryLevel: Int = 75,
        val batteryCharging: Boolean = false,
        val ttsUtteranceId: String = "phone-tts",
        val connectionDelayMillis: Long = 0,
        /** Simulated permission grants. */
        val grantedPermissions: Set<String> = setOf("RECORD_AUDIO", "CAMERA"),
    ) {
        companion object {
            // Safety cap to keep simulated audio collection bounded.
            const val MAX_AUDIO_BYTES: Int = 1_048_576
        }
    }

    private var connected = false
    private var operationCounter = 0
    private var ttsInitialized = false
    private var ttsActive = false

    override val profile: SenseProfile = SenseProfile(
        endpointId = config.endpointId,
        backendName = "Phone",
        backendKind = BackendKind.PHONE,
        state = EndpointState.DISCONNECTED,
        capabilities = buildSet {
            if (config.hasMicrophone) add(SenseCapability.AudioInput)
            if (config.hasSpeaker) add(SenseCapability.AudioOutput)
            if (config.hasCamera) add(SenseCapability.ImageInput)
            if (config.hasScreen) add(SenseCapability.VisualOutput)
            if (config.hasScreen) add(SenseCapability.TextOutput)
            if (config.hasKeyboard) add(SenseCapability.TextInput)
            if (config.hasKeyboard) add(SenseCapability.InteractionInput)
            if (config.hasBattery) add(SenseCapability.StatusInput)
        },
        limits = mapOf(
            SenseCapability.AudioInput to SenseLimits(maxDurationMillis = 60_000, maxBytes = 1_048_576),
            SenseCapability.ImageInput to SenseLimits(maxBytes = 65_536),
            SenseCapability.AudioOutput to SenseLimits(maxBytes = 1_048_576, maxConcurrent = 1),
            SenseCapability.VisualOutput to SenseLimits(maxBytes = 10_000_000),
            SenseCapability.TextOutput to SenseLimits(maxBytes = 65_536),
            SenseCapability.StatusInput to SenseLimits(maxDurationMillis = 5_000),
        ),
        concurrency = ConcurrencyProfile(
            resourceDomains = mapOf(
                SenseCapability.AudioInput to "phone-audio",
                SenseCapability.AudioOutput to "phone-audio",
                SenseCapability.ImageInput to "phone-camera",
                SenseCapability.VisualOutput to "phone-screen",
                SenseCapability.TextOutput to "phone-screen",
                SenseCapability.TextInput to "phone-ui",
                SenseCapability.InteractionInput to "phone-ui",
            ),
            explicitConflicts = setOf(
                SenseCapability.AudioInput to SenseCapability.AudioOutput,
            ),
        ),
        displayName = config.displayName,
    )

    override suspend fun connect() {
        if (config.connectionDelayMillis > 0) delay(config.connectionDelayMillis)
        connected = true
        if (config.hasSpeaker) ttsInitialized = true
    }

    override suspend fun disconnect() {
        if (ttsActive) {
            ttsActive = false
        }
        ttsInitialized = false
        connected = false
    }

    private fun ensureConnected() {
        if (!connected) throw SensesError.Disconnected("Phone endpoint not connected")
    }

    private fun checkPermission(permission: String) {
        if (permission !in config.grantedPermissions) {
            throw SensesError.PermissionDenied("Missing permission: $permission")
        }
    }

    private fun nextOpId() = "phone-op-${++operationCounter}"

    override suspend fun audioInput(request: AudioInputRequest): AudioInputResult {
        if (!config.hasMicrophone) throw SensesError.Unavailable("No microphone")
        checkPermission("RECORD_AUDIO")
        ensureConnected()
        val opId = nextOpId()
        val startedAt = System.currentTimeMillis()

        val bytesPerSample = config.audioFormat.bitDepth / 8 * config.audioFormat.channels
        val bytesPerSecond = config.audioFormat.sampleRate * bytesPerSample
        val bytesPerMs = bytesPerSecond / 1000L

        val maxByDuration = request.maxDurationMillis * bytesPerMs
        val maxByBytes = request.maxBytes.coerceAtMost(PhoneConfig.MAX_AUDIO_BYTES).toLong()
        val targetBytes = minOf(maxByDuration, maxByBytes).toInt().coerceAtLeast(0)

        val pcm = if (config.audioFixture.isEmpty()) {
            ByteArray(0)
        } else {
            ByteArray(targetBytes) { config.audioFixture[it % config.audioFixture.size] }
        }

        val wav = PcmToWav.fromPcm16(
            pcm,
            config.audioFormat.sampleRate,
            config.audioFormat.channels,
            config.audioFormat.bitDepth,
        )

        val durationMillis = if (bytesPerSecond > 0) pcm.size * 1000L / bytesPerSecond else 0L

        val completedAt = System.currentTimeMillis()
        return AudioInputResult(
            audio = wav,
            format = config.audioFormat,
            durationMillis = durationMillis,
            provenance = Provenance(
                operationId = opId,
                endpointId = config.endpointId,
                backendName = "Phone",
                backendKind = BackendKind.PHONE,
                capability = SenseCapability.AudioInput,
                origin = ResultOrigin.MICROPHONE,
                mediaFormat = MediaFormat(
                    encoding = config.audioFormat.encoding,
                    mime = config.audioFormat.mime,
                    durationMillis = durationMillis,
                    sampleRate = config.audioFormat.sampleRate,
                    channels = config.audioFormat.channels,
                ),
                startedAt = startedAt,
                completedAt = completedAt,
            ),
        )
    }

    override suspend fun audioOutput(request: AudioOutputRequest): AudioOutputResult {
        if (!config.hasSpeaker) throw SensesError.Unavailable("No speaker")
        ensureConnected()
        val opId = nextOpId()
        val startedAt = System.currentTimeMillis()
        // In real implementation: AudioTrack playback with pacing
        ttsActive = true
        ttsActive = false
        val completedAt = System.currentTimeMillis()
        return AudioOutputResult(
            provenance = Provenance(
                operationId = opId,
                endpointId = config.endpointId,
                backendName = "Phone",
                backendKind = BackendKind.PHONE,
                capability = SenseCapability.AudioOutput,
                origin = ResultOrigin.SPEAKER,
                mediaFormat = MediaFormat(
                    encoding = request.format.encoding,
                    mime = request.format.mime,
                    sampleRate = request.format.sampleRate,
                    channels = request.format.channels,
                ),
                startedAt = startedAt,
                completedAt = completedAt,
            ),
        )
    }

    override suspend fun imageInput(request: ImageInputRequest): ImageInputResult {
        if (!config.hasCamera) throw SensesError.Unavailable("No camera")
        checkPermission("CAMERA")
        ensureConnected()
        val opId = nextOpId()
        val startedAt = System.currentTimeMillis()

        val image = if (config.imageFixture.size <= request.maxBytes) {
            config.imageFixture
        } else {
            config.imageFixture.copyOfRange(0, request.maxBytes)
        }

        val isRaw = request.deviceOptions["raw"] as? Boolean ?: false

        val completedAt = System.currentTimeMillis()
        return ImageInputResult(
            image = image,
            format = config.imageFormat.copy(width = request.resolution, height = request.resolution),
            isRaw = isRaw,
            provenance = Provenance(
                operationId = opId,
                endpointId = config.endpointId,
                backendName = "Phone",
                backendKind = BackendKind.PHONE,
                capability = SenseCapability.ImageInput,
                origin = ResultOrigin.CAMERA,
                mediaFormat = MediaFormat(
                    encoding = config.imageFormat.encoding,
                    mime = config.imageFormat.mime,
                    width = request.resolution,
                    height = request.resolution,
                ),
                startedAt = startedAt,
                completedAt = completedAt,
            ),
        )
    }

    override suspend fun visualOutput(request: VisualOutputRequest): VisualOutputResult {
        if (!config.hasScreen) throw SensesError.Unavailable("No screen")
        ensureConnected()
        val opId = nextOpId()
        val startedAt = System.currentTimeMillis()
        val completedAt = System.currentTimeMillis()
        return VisualOutputResult(
            provenance = Provenance(
                operationId = opId,
                endpointId = config.endpointId,
                backendName = "Phone",
                backendKind = BackendKind.PHONE,
                capability = SenseCapability.VisualOutput,
                origin = ResultOrigin.DISPLAY,
                startedAt = startedAt,
                completedAt = completedAt,
            ),
        )
    }

    override suspend fun textOutput(request: TextOutputRequest): TextOutputResult {
        if (!config.hasScreen) throw SensesError.Unavailable("No screen")
        ensureConnected()
        val opId = nextOpId()
        val startedAt = System.currentTimeMillis()
        val completedAt = System.currentTimeMillis()
        return TextOutputResult(
            provenance = Provenance(
                operationId = opId,
                endpointId = config.endpointId,
                backendName = "Phone",
                backendKind = BackendKind.PHONE,
                capability = SenseCapability.TextOutput,
                origin = ResultOrigin.DISPLAY,
                startedAt = startedAt,
                completedAt = completedAt,
            ),
        )
    }

    override suspend fun textInput(request: TextInputRequest): TextInputResult {
        if (!config.hasKeyboard) throw SensesError.Unavailable("No keyboard")
        ensureConnected()
        val opId = nextOpId()
        val startedAt = System.currentTimeMillis()
        val completedAt = System.currentTimeMillis()
        return TextInputResult(
            text = "",
            provenance = Provenance(
                operationId = opId,
                endpointId = config.endpointId,
                backendName = "Phone",
                backendKind = BackendKind.PHONE,
                capability = SenseCapability.TextInput,
                origin = ResultOrigin.KEYBOARD,
                startedAt = startedAt,
                completedAt = completedAt,
            ),
        )
    }

    override suspend fun interactionInput(request: InteractionInputRequest): InteractionInputResult {
        if (!config.hasKeyboard) throw SensesError.Unavailable("No touch input")
        ensureConnected()
        val opId = nextOpId()
        val startedAt = System.currentTimeMillis()
        val completedAt = System.currentTimeMillis()
        val event = if (request.acceptedGestures.isEmpty() || InteractionType.TAP_SINGLE in request.acceptedGestures) {
            InteractionEvent.Tap(TapGesture.SINGLE)
        } else if (InteractionType.TEXT_ENTRY in request.acceptedGestures) {
            InteractionEvent.TextEntry("")
        } else {
            InteractionEvent.Approval(approved = true)
        }
        return InteractionInputResult(
            event = event,
            provenance = Provenance(
                operationId = opId,
                endpointId = config.endpointId,
                backendName = "Phone",
                backendKind = BackendKind.PHONE,
                capability = SenseCapability.InteractionInput,
                origin = ResultOrigin.SENSOR,
                startedAt = startedAt,
                completedAt = completedAt,
            ),
        )
    }

    override suspend fun statusInput(request: StatusInputRequest): StatusInputResult {
        if (!config.hasBattery) throw SensesError.Unavailable("No battery status")
        ensureConnected()
        val opId = nextOpId()
        val startedAt = System.currentTimeMillis()
        val completedAt = System.currentTimeMillis()
        return StatusInputResult(
            status = EndpointStatus(
                batteryLevel = config.batteryLevel,
                batteryCharging = config.batteryCharging,
            ),
            provenance = Provenance(
                operationId = opId,
                endpointId = config.endpointId,
                backendName = "Phone",
                backendKind = BackendKind.PHONE,
                capability = SenseCapability.StatusInput,
                origin = ResultOrigin.SENSOR,
                startedAt = startedAt,
                completedAt = completedAt,
            ),
        )
    }
}
