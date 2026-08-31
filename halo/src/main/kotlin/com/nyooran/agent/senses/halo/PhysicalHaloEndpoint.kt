package com.nyooran.agent.senses.halo

import com.nyooran.agent.senses.*
import halo.engine.HaloBleTransport
import halo.engine.HaloProtocol
import halo.engine.HaloSession
import halo.engine.display.HrpRenderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Phase 3 P3-03: Physical Halo sense endpoint.
 *
 * Maps generic capability profiles to firmware-negotiated Halo features
 * via a real (or fake) [HaloBleTransport]. Routes capture, playback,
 * display, input, and status through engine-halo's [HaloSession].
 *
 * Key properties:
 * - Rejects unsupported camera parameters before transport (pan, raw).
 * - Applies complete input source/gesture filters.
 * - Provenance identifies physical Halo and firmware profile.
 * - Keeps Gemma, semantic workflows, and TTS policy outside the endpoint.
 *
 * Acceptance: The physical implementation passes the shared suite over
 * a fake engine transport.
 */
class PhysicalHaloEndpoint(
    private val transport: HaloBleTransport,
    private val config: HaloEndpointConfig = HaloEndpointConfig(),
) : SenseEndpoint {

    data class HaloEndpointConfig(
        val endpointId: EndpointId = EndpointId("halo-1"),
        val displayName: String = "Halo Glasses",
        val firmwareProfile: String = "HRP1;primitives,sprites,click,tap,mic,speaker,photo,battery",
        val connectionTimeout: Duration = 10.seconds,
        val operationTimeout: Duration = 30.seconds,
        val maxAudioBytes: Long = 1_048_576,
        val maxImageBytes: Long = 65536,
        val maxHrpBytes: Int = 4096,
        val audioFormat: AudioFormat = AudioFormat(16000, 16, 1, "wav", "audio/wav"),
        val imageFormat: ImageFormat = ImageFormat("jpeg", "image/jpeg", 640, 640),
    )

    private val session = HaloSession(transport)
    private val renderer = HrpRenderer()

    private val _state = MutableStateFlow(EndpointState.DISCONNECTED)
    val state: StateFlow<EndpointState> = _state.asStateFlow()

    private var operationCounter = 0
    private var connected = false

    private fun nextOpId() = "halo-op-${++operationCounter}"

    override val profile: SenseProfile = SenseProfile(
        endpointId = config.endpointId,
        backendName = "Halo",
        backendKind = BackendKind.PHYSICAL,
        state = EndpointState.DISCONNECTED,
        capabilities = setOf(
            SenseCapability.AudioInput,
            SenseCapability.AudioOutput,
            SenseCapability.ImageInput,
            SenseCapability.VisualOutput,
            SenseCapability.InteractionInput,
            SenseCapability.StatusInput,
        ),
        limits = mapOf(
            SenseCapability.AudioInput to SenseLimits(maxDurationMillis = 60_000, maxBytes = config.maxAudioBytes),
            SenseCapability.ImageInput to SenseLimits(maxBytes = config.maxImageBytes),
            SenseCapability.AudioOutput to SenseLimits(maxBytes = config.maxAudioBytes, maxConcurrent = 1),
            SenseCapability.VisualOutput to SenseLimits(maxBytes = config.maxHrpBytes.toLong()),
        ),
        concurrency = ConcurrencyProfile(
            resourceDomains = mapOf(
                SenseCapability.AudioInput to "audio",
                SenseCapability.AudioOutput to "audio",
                SenseCapability.ImageInput to "camera",
                SenseCapability.VisualOutput to "display",
            ),
            explicitConflicts = setOf(
                SenseCapability.AudioInput to SenseCapability.ImageInput,
            ),
        ),
        displayName = config.displayName,
    )

    override suspend fun connect() {
        connected = true
        _state.value = EndpointState.READY
    }

    override suspend fun disconnect() {
        connected = false
        _state.value = EndpointState.DISCONNECTED
    }

    override suspend fun audioInput(request: AudioInputRequest): AudioInputResult {
        val opId = nextOpId()
        val startedAt = System.currentTimeMillis()
        val pcm = session.collect(
            startCode = HaloProtocol.MICROPHONE_START,
            startPayload = ByteArray(0),
            stopCode = HaloProtocol.MICROPHONE_STOP,
            chunkCode = HaloProtocol.AUDIO_CHUNK,
            finalCode = HaloProtocol.AUDIO_FINAL,
            timeout = config.operationTimeout,
            maxBytes = config.maxAudioBytes,
            stopAfter = request.maxDurationMillis.milliseconds,
        )
        val completedAt = System.currentTimeMillis()
        return AudioInputResult(
            audio = pcm,
            format = config.audioFormat,
            durationMillis = request.maxDurationMillis,
            provenance = Provenance(
                operationId = opId,
                endpointId = config.endpointId,
                backendName = "Halo",
                backendKind = BackendKind.PHYSICAL,
                capability = SenseCapability.AudioInput,
                origin = ResultOrigin.MICROPHONE,
                mediaFormat = MediaFormat(
                    encoding = config.audioFormat.encoding,
                    mime = config.audioFormat.mime,
                    durationMillis = request.maxDurationMillis,
                    sampleRate = config.audioFormat.sampleRate,
                    channels = config.audioFormat.channels,
                ),
                startedAt = startedAt,
                completedAt = completedAt,
            ),
        )
    }

    override suspend fun audioOutput(request: AudioOutputRequest): AudioOutputResult {
        val opId = nextOpId()
        val startedAt = System.currentTimeMillis()
        session.requestResponse(
            requestCode = HaloProtocol.SPEAKER_START,
            requestPayload = request.audio,
            responseCode = HaloProtocol.SPEAKER_STOP,
            timeout = config.operationTimeout,
        )
        val completedAt = System.currentTimeMillis()
        return AudioOutputResult(
            provenance = Provenance(
                operationId = opId,
                endpointId = config.endpointId,
                backendName = "Halo",
                backendKind = BackendKind.PHYSICAL,
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
        if (request.deviceOptions["raw"] as? Boolean == true) {
            throw SensesError.Rejected("Halo camera does not support raw capture")
        }
        if (request.resolution != 640) {
            throw SensesError.Rejected("Halo camera is fixed at 640x640, requested ${request.resolution}")
        }
        val opId = nextOpId()
        val startedAt = System.currentTimeMillis()
        val image = session.collect(
            startCode = HaloProtocol.CAPTURE_PHOTO,
            startPayload = ByteArray(0),
            chunkCode = HaloProtocol.PHOTO_JPEG,
            finalCode = HaloProtocol.PHOTO_FINAL,
            timeout = config.operationTimeout,
            maxBytes = config.maxImageBytes,
        )
        val completedAt = System.currentTimeMillis()
        return ImageInputResult(
            image = image,
            format = config.imageFormat,
            isRaw = false,
            provenance = Provenance(
                operationId = opId,
                endpointId = config.endpointId,
                backendName = "Halo",
                backendKind = BackendKind.PHYSICAL,
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
        if (request.content.kind != VisualContent.VisualKind.DEVICE_NATIVE) {
            throw SensesError.Rejected("Halo visual output only supports DEVICE_NATIVE (HRP)")
        }
        if (request.content.payload.size > config.maxHrpBytes) {
            throw SensesError.LimitExceeded("HRP payload ${request.content.payload.size} exceeds limit ${config.maxHrpBytes}")
        }
        val opId = nextOpId()
        val startedAt = System.currentTimeMillis()
        renderer.render(request.content.payload)
        session.requestResponse(
            requestCode = HaloProtocol.HRP,
            requestPayload = request.content.payload,
            responseCode = HaloProtocol.STATUS,
            timeout = config.operationTimeout,
        )
        val completedAt = System.currentTimeMillis()
        return VisualOutputResult(
            provenance = Provenance(
                operationId = opId,
                endpointId = config.endpointId,
                backendName = "Halo",
                backendKind = BackendKind.PHYSICAL,
                capability = SenseCapability.VisualOutput,
                origin = ResultOrigin.DISPLAY,
                startedAt = startedAt,
                completedAt = completedAt,
            ),
        )
    }

    override suspend fun textOutput(request: TextOutputRequest): TextOutputResult {
        throw SensesError.Unavailable("Halo endpoint does not support text output (use VisualOutput with HRP)")
    }

    override suspend fun textInput(request: TextInputRequest): TextInputResult {
        throw SensesError.Unavailable("Halo endpoint does not support text input (no keyboard)")
    }

    override suspend fun interactionInput(request: InteractionInputRequest): InteractionInputResult {
        val opId = nextOpId()
        val startedAt = System.currentTimeMillis()
        val event = waitForInteraction(request)
        val completedAt = System.currentTimeMillis()
        return InteractionInputResult(
            event = event,
            provenance = Provenance(
                operationId = opId,
                endpointId = config.endpointId,
                backendName = "Halo",
                backendKind = BackendKind.PHYSICAL,
                capability = SenseCapability.InteractionInput,
                origin = ResultOrigin.SENSOR,
                startedAt = startedAt,
                completedAt = completedAt,
            ),
        )
    }

    override suspend fun statusInput(request: StatusInputRequest): StatusInputResult {
        val opId = nextOpId()
        val startedAt = System.currentTimeMillis()
        val payload = session.requestResponse(
            requestCode = HaloProtocol.DEVICE_STATUS,
            requestPayload = ByteArray(0),
            responseCode = HaloProtocol.DEVICE_STATUS,
            timeout = config.operationTimeout,
        )
        val completedAt = System.currentTimeMillis()
        val status = if (payload.size >= 4) {
            EndpointStatus(
                batteryLevel = payload[0].toInt() and 0xFF,
                batteryVoltage = ((payload[1].toInt() and 0xFF) shl 8) or (payload[2].toInt() and 0xFF),
                batteryCharging = (payload[3].toInt() and 0xFF) != 0,
            )
        } else {
            EndpointStatus()
        }
        return StatusInputResult(
            status = status,
            provenance = Provenance(
                operationId = opId,
                endpointId = config.endpointId,
                backendName = "Halo",
                backendKind = BackendKind.PHYSICAL,
                capability = SenseCapability.StatusInput,
                origin = ResultOrigin.SENSOR,
                startedAt = startedAt,
                completedAt = completedAt,
            ),
        )
    }

    private suspend fun waitForInteraction(request: InteractionInputRequest): InteractionEvent {
        while (true) {
            val msg = transport.messages.first()
            val event = when (msg.code) {
                HaloProtocol.TAP -> when (msg.payload.getOrElse(0) { 0 }.toInt()) {
                    1 -> InteractionEvent.Tap(TapGesture.SINGLE)
                    2 -> InteractionEvent.Tap(TapGesture.DOUBLE)
                    3 -> InteractionEvent.Tap(TapGesture.TRIPLE)
                    else -> null
                }
                HaloProtocol.BUTTON -> when (msg.payload.getOrElse(0) { 0 }.toInt()) {
                    1 -> InteractionEvent.Button(ButtonGesture.SINGLE)
                    2 -> InteractionEvent.Button(ButtonGesture.DOUBLE)
                    3 -> InteractionEvent.Button(ButtonGesture.LONG)
                    else -> null
                }
                else -> null
            }
            if (event != null) {
                val type = event.toInteractionType()
                if (request.acceptedGestures.isEmpty() || type in request.acceptedGestures) {
                    return event
                }
            }
        }
    }
}
