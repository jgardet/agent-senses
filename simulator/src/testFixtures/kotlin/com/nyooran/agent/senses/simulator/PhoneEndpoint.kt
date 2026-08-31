package com.nyooran.agent.senses.simulator

import com.nyooran.agent.senses.*
import kotlinx.coroutines.delay

/**
 * Phase 3 P3-02: Phone sense endpoint.
 *
 * A sense endpoint backed by phone-local hardware: microphone, camera,
 * loudspeaker, screen, and battery. On a real device, the methods delegate
 * to Android APIs (AudioRecord, CameraX, AudioTrack, TextToSpeech, etc.).
 *
 * In the simulator/JVM module, this class provides a deterministic stub
 * that can be used for orchestration tests. The real Android implementation
 * lives in the `:android` module and wraps these same methods with
 * permission-aware hardware calls.
 *
 * Key properties:
 * - Permission-aware: checks permissions before capture (throws if missing).
 * - Camera: normalizes orientation, center-crops, encodes to 640x640.
 * - TTS: deterministic init, utterance tracking, cancellation, cleanup.
 * - Battery: labeled as phone state, not Halo state.
 * - Does NOT advertise Halo-specific capabilities (HRP display, wearable tap).
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
        val audioFixture: ByteArray = ByteArray(160),
        val audioFormat: AudioFormat = AudioFormat(16000, 16, 1, "wav", "audio/wav"),
        val audioDurationMillis: Long = 100,
        val imageFixture: ByteArray = ByteArray(0),
        val imageFormat: ImageFormat = ImageFormat("jpeg", "image/jpeg", 640, 640),
        val batteryLevel: Int = 75,
        val batteryCharging: Boolean = false,
        val ttsUtteranceId: String = "phone-tts",
        val connectionDelayMillis: Long = 0,
        /** Simulated permission grants. */
        val grantedPermissions: Set<String> = setOf("RECORD_AUDIO", "CAMERA"),
    )

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
            SenseCapability.ImageInput to SenseLimits(maxBytes = 65536),
            SenseCapability.AudioOutput to SenseLimits(maxBytes = 1_048_576, maxConcurrent = 1),
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
            ).filterKeys { it in (setOf(
                SenseCapability.AudioInput, SenseCapability.AudioOutput,
                SenseCapability.ImageInput, SenseCapability.VisualOutput,
                SenseCapability.TextOutput, SenseCapability.TextInput,
                SenseCapability.InteractionInput,
            )) },
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
            // Cancel any active TTS utterance
            ttsActive = false
        }
        ttsInitialized = false
        connected = false
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
        val opId = nextOpId()
        val startedAt = System.currentTimeMillis()
        // In real implementation: AudioRecord with permission check
        val completedAt = System.currentTimeMillis()
        return AudioInputResult(
            audio = config.audioFixture,
            format = config.audioFormat,
            durationMillis = config.audioDurationMillis,
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
                    durationMillis = config.audioDurationMillis,
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
        val opId = nextOpId()
        val startedAt = System.currentTimeMillis()
        // In real implementation: CameraX capture, normalize orientation,
        // center-crop to 640x640, encode as JPEG
        val completedAt = System.currentTimeMillis()
        return ImageInputResult(
            image = config.imageFixture,
            format = config.imageFormat,
            isRaw = request.deviceOptions["raw"] as? Boolean ?: false,
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
                    width = config.imageFormat.width,
                    height = config.imageFormat.height,
                ),
                startedAt = startedAt,
                completedAt = completedAt,
            ),
        )
    }

    override suspend fun visualOutput(request: VisualOutputRequest): VisualOutputResult {
        if (!config.hasScreen) throw SensesError.Unavailable("No screen")
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
        val opId = nextOpId()
        val startedAt = System.currentTimeMillis()
        val completedAt = System.currentTimeMillis()
        return InteractionInputResult(
            event = InteractionEvent.Tap(TapGesture.SINGLE),
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
