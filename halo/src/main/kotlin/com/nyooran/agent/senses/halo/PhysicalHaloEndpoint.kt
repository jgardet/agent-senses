package com.nyooran.agent.senses.halo

import com.nyooran.agent.senses.*
import com.nyooran.agent.senses.audio.PcmToWav
import com.nyooran.agent.senses.audio.WavReader
import halo.engine.HaloLimitException
import halo.engine.HaloProtocol
import halo.engine.HaloSession
import halo.engine.HaloMessage
import halo.engine.HaloTransportException
import halo.engine.HsdHrpCompiler
import halo.engine.HaloBleTransport
import halo.engine.SpritePacker
import halo.engine.StubSpritePacker
import halo.engine.display.HrpFailure
import halo.engine.display.HrpRenderer
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Phase 3 P3-03: Physical Halo sense endpoint.
 *
 * Maps generic capability profiles to firmware-negotiated Halo features
 * via a real (or fake) [HaloBleTransport]. Routes capture, playback,
 * display, input, and status through halo-engine's [HaloSession].
 *
 * Key properties:
 * - Rejects unsupported camera parameters before transport (pan, raw).
 * - Wraps microphone PCM in WAV before returning it.
 * - Streams speaker audio as 10 ms frames with delay pacing.
 * - Applies complete input source/gesture filters.
 * - Provenance identifies physical Halo and firmware profile.
 * - Keeps Gemma, semantic workflows, and TTS policy outside the endpoint.
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
        val maxHsdBytes: Int = 65_536,
        val audioFormat: AudioFormat = AudioFormat(16000, 16, 1, "wav", "audio/wav"),
        val imageFormat: ImageFormat = ImageFormat("jpeg", "image/jpeg", 640, 640),
        val spritePacker: SpritePacker? = null,
        val runtimeInstaller: suspend (HaloBleTransport) -> Unit = {},
        /** Optional callback invoked when an interaction event arrives from the device. */
        val onInteraction: suspend (InteractionEvent) -> Unit = { _ -> },
        val audioFrameMillis: Int = 10,
        val speakerVolume: Int = 80,
    )

    private val session = HaloSession(transport)
    private val renderer = HrpRenderer()
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(EndpointState.DISCONNECTED)
    val state: StateFlow<EndpointState> = _state.asStateFlow()

    private var operationCounter = 0
    private var messageJob: Job? = null
    private var connectionJob: Job? = null

    private val interactionQueue = ConcurrentLinkedQueue<InteractionEvent>()
    private val _recordedInteractions = mutableListOf<InteractionEvent>()
    val recordedInteractions: List<InteractionEvent> get() = _recordedInteractions.toList()

    private val _recordedHrp = mutableListOf<ByteArray>()
    val recordedHrp: List<ByteArray> get() = _recordedHrp.toList()

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
            SenseCapability.VisualOutput to SenseLimits(maxBytes = config.maxHsdBytes.toLong()),
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
        if (_state.value == EndpointState.READY) return

        messageJob = scope.launch { transport.messages.collect { handleMessage(it) } }

        withTimeout(config.connectionTimeout) {
            transport.connect()
        }

        config.runtimeInstaller(transport)

        connectionJob = scope.launch {
            transport.connectionEvents.first { connected -> !connected }
            _state.value = EndpointState.DISCONNECTED
            messageJob?.cancel()
            messageJob = null
        }

        _state.value = EndpointState.READY
    }

    override suspend fun disconnect() = withContext(NonCancellable) {
        runCatching { transport.disconnect() }
        cancelJobs()
        _state.value = EndpointState.DISCONNECTED
    }

    private fun cancelJobs() {
        messageJob?.cancel()
        messageJob = null
        connectionJob?.cancel()
        connectionJob = null
        interactionQueue.clear()
    }

    private fun ensureReady() {
        if (_state.value != EndpointState.READY) {
            throw SensesError.Disconnected("endpoint not ready")
        }
    }

    override suspend fun audioInput(request: AudioInputRequest): AudioInputResult {
        ensureReady()
        val opId = nextOpId()
        val startedAt = System.currentTimeMillis()

        val timeout = effectiveTimeout(request.timeoutMillis, request.maxDurationMillis + 2000)
        val maxBytes = minOf(request.maxBytes.toLong(), config.maxAudioBytes)

        val startPayload = byteArrayOf(
            (request.gain + 10).toByte(),
            if (request.aec) 1.toByte() else 0.toByte(),
            if (request.voice) 1.toByte() else 0.toByte(),
        )

        val pcm = try {
            session.collect(
                startCode = HaloProtocol.MICROPHONE_START,
                startPayload = startPayload,
                stopCode = HaloProtocol.MICROPHONE_STOP,
                chunkCode = HaloProtocol.AUDIO_CHUNK,
                finalCode = HaloProtocol.AUDIO_FINAL,
                timeout = timeout.milliseconds,
                maxBytes = maxBytes,
                stopAfter = request.maxDurationMillis.milliseconds,
            )
        } catch (e: HaloLimitException) {
            throw SensesError.LimitExceeded(e.message ?: "audio limit exceeded")
        } catch (e: HaloTransportException) {
            _state.value = EndpointState.DISCONNECTED
            throw SensesError.Disconnected(e.message ?: "transport disconnected")
        } catch (e: TimeoutCancellationException) {
            throw SensesError.Timeout("audio input timed out")
        }

        val wav = PcmToWav.fromPcm16(
            pcm,
            config.audioFormat.sampleRate,
            config.audioFormat.channels,
            config.audioFormat.bitDepth,
        )
        val durationMillis = if (pcm.isEmpty()) 0L else {
            pcm.size * 1000L / (config.audioFormat.sampleRate * config.audioFormat.channels * (config.audioFormat.bitDepth / 8))
        }

        val completedAt = System.currentTimeMillis()
        return AudioInputResult(
            audio = wav,
            format = config.audioFormat,
            durationMillis = durationMillis,
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
        ensureReady()
        val opId = nextOpId()
        val startedAt = System.currentTimeMillis()

        val targetSampleRate = 16000
        val targetChannels = 1
        val targetBitDepth = 16
        val frameMillis = config.audioFrameMillis
        val volume = request.volume.coerceIn(0, 100)

        val startPayload = byteArrayOf(
            0, // PCM encoder
            (targetSampleRate shr 8).toByte(),
            targetSampleRate.toByte(),
            targetBitDepth.toByte(),
            targetChannels.toByte(),
            volume.toByte(),
        )

        val timeout = effectiveTimeout(request.timeoutMillis, 30_000)

        try {
            withTimeout(timeout.milliseconds) {
                transport.sendMessage(HaloProtocol.SPEAKER_START, startPayload)
                playAudioFrames(request.audio, request.format, targetSampleRate, targetChannels, targetBitDepth, frameMillis)
            }
        } catch (e: TimeoutCancellationException) {
            throw SensesError.Timeout("audio output timed out")
        } catch (e: CancellationException) {
            throw e
        } finally {
            withContext(NonCancellable) {
                runCatching { transport.sendMessage(HaloProtocol.SPEAKER_STOP, byteArrayOf()) }
            }
        }

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
                    sampleRate = targetSampleRate,
                    channels = targetChannels,
                ),
                startedAt = startedAt,
                completedAt = completedAt,
            ),
        )
    }

    private suspend fun playAudioFrames(
        audio: ByteArray,
        format: AudioFormat,
        targetSampleRate: Int,
        targetChannels: Int,
        targetBitDepth: Int,
        frameMillis: Int,
    ) {
        val bytesPerSample = targetBitDepth / 8 * targetChannels
        val bytesPerFrame = targetSampleRate * frameMillis / 1000 * bytesPerSample

        val frames = when {
            format.encoding.equals("wav", ignoreCase = true) -> {
                WavReader.readPcm(ByteArrayInputStream(audio), targetSampleRate, frameMillis)
            }
            format.encoding.equals("pcm", ignoreCase = true) ||
            format.encoding.equals("pcm-s16le", ignoreCase = true) -> {
                if (format.sampleRate != targetSampleRate || format.channels != targetChannels || format.bitDepth != targetBitDepth) {
                    throw SensesError.Rejected("raw PCM must be $targetSampleRate Hz ${targetChannels}-channel ${targetBitDepth}-bit")
                }
                rawPcmFrames(audio, bytesPerFrame)
            }
            else -> throw SensesError.Rejected("unsupported audio encoding: ${format.encoding}")
        }

        frames.flowOn(Dispatchers.IO).collect { frame ->
            currentCoroutineContext().ensureActive()
            transport.sendAudioFrame(frame)
            val frameSamples = frame.size / bytesPerSample
            val sleepMs = frameSamples * 1000L / targetSampleRate
            if (sleepMs > 0) delay(sleepMs)
        }
    }

    private fun rawPcmFrames(pcm: ByteArray, bytesPerFrame: Int): Flow<ByteArray> = flow {
        var offset = 0
        while (offset < pcm.size) {
            val end = minOf(offset + bytesPerFrame, pcm.size)
            emit(pcm.copyOfRange(offset, end))
            offset = end
        }
    }

    override suspend fun imageInput(request: ImageInputRequest): ImageInputResult {
        ensureReady()
        if (request.deviceOptions["raw"] as? Boolean == true) {
            throw SensesError.Rejected("Halo camera does not support raw capture")
        }
        if ("pan" in request.deviceOptions) {
            throw SensesError.Rejected("Halo camera does not support pan")
        }
        if (request.resolution != 640) {
            throw SensesError.Rejected("Halo camera is fixed at 640x640, requested ${request.resolution}")
        }

        val opId = nextOpId()
        val startedAt = System.currentTimeMillis()

        val qualityIndex = (request.deviceOptions["qualityIndex"] as? Int
            ?: request.deviceOptions["quality_index"] as? Int
            ?: 4).coerceIn(0, 4)

        val startPayload = byteArrayOf(
            qualityIndex.toByte(),
            (320 shr 8).toByte(),
            320.toByte(),
            (140 shr 8).toByte(),
            140.toByte(),
            0,
        )

        val timeout = effectiveTimeout(request.timeoutMillis, 30_000)
        val maxBytes = minOf(request.maxBytes.toLong(), config.maxImageBytes)

        val image = try {
            session.collect(
                startCode = HaloProtocol.CAPTURE_PHOTO,
                startPayload = startPayload,
                chunkCode = HaloProtocol.PHOTO_JPEG,
                finalCode = HaloProtocol.PHOTO_FINAL,
                timeout = timeout.milliseconds,
                maxBytes = maxBytes,
            )
        } catch (e: HaloLimitException) {
            throw SensesError.LimitExceeded(e.message ?: "image limit exceeded")
        } catch (e: HaloTransportException) {
            _state.value = EndpointState.DISCONNECTED
            throw SensesError.Disconnected(e.message ?: "transport disconnected")
        } catch (e: TimeoutCancellationException) {
            throw SensesError.Timeout("image capture timed out")
        }

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
        ensureReady()
        val opId = nextOpId()
        val startedAt = System.currentTimeMillis()

        val hrpPayload = compileVisualPayload(request)
        if (hrpPayload.size > config.maxHrpBytes) {
            throw SensesError.LimitExceeded("HRP payload ${hrpPayload.size} exceeds limit ${config.maxHrpBytes}")
        }

        _recordedHrp.add(hrpPayload)
        try {
            renderer.render(hrpPayload)
        } catch (e: HrpFailure) {
            throw SensesError.Protocol("HRP render failed: ${e.message}")
        }

        try {
            transport.sendMessage(HaloProtocol.HRP, hrpPayload)
        } catch (e: HaloTransportException) {
            _state.value = EndpointState.DISCONNECTED
            throw SensesError.Disconnected(e.message ?: "transport disconnected")
        }

        val completedAt = System.currentTimeMillis()
        return VisualOutputResult(
            provenance = Provenance(
                operationId = opId,
                endpointId = config.endpointId,
                backendName = "Halo",
                backendKind = BackendKind.PHYSICAL,
                capability = SenseCapability.VisualOutput,
                origin = ResultOrigin.DISPLAY,
                transformations = if (request.content.format == "hsd" || request.content.kind == VisualContent.VisualKind.TEXT)
                    listOf(Transformation.PRESENTATION_COMPILATION) else emptyList(),
                startedAt = startedAt,
                completedAt = completedAt,
            ),
        )
    }

    private fun compileVisualPayload(request: VisualOutputRequest): ByteArray {
        val payload = request.content.payload
        return when (request.content.kind) {
            VisualContent.VisualKind.DEVICE_NATIVE -> {
                when (request.content.format ?: "hrp") {
                    "hsd" -> compileHsd(payload)
                    "hrp" -> payload
                    else -> throw SensesError.Rejected("unsupported device_native format: ${request.content.format}")
                }
            }
            VisualContent.VisualKind.TEXT -> {
                val text = payload.toString(Charsets.UTF_8)
                if (text.isEmpty()) throw SensesError.Rejected("text payload must not be empty")
                if (text.toByteArray(Charsets.UTF_8).size > config.maxHsdBytes) {
                    throw SensesError.LimitExceeded("text payload exceeds HSD limit")
                }
                compileHsd(textToHsd(text))
            }
            VisualContent.VisualKind.IMAGE -> throw SensesError.Rejected("Halo visual output does not support IMAGE (use device_native HSD with a sprite)")
        }
    }

    private fun compileHsd(payload: ByteArray): ByteArray {
        if (payload.size > config.maxHsdBytes) {
            throw SensesError.LimitExceeded("HSD payload ${payload.size} exceeds limit ${config.maxHsdBytes}")
        }
        val scene = try {
            json.parseToJsonElement(payload.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw SensesError.Rejected("invalid HSD JSON: ${e.message}")
        }
        return try {
            HsdHrpCompiler(config.spritePacker ?: StubSpritePacker()).compile(scene)
        } catch (e: IllegalArgumentException) {
            throw SensesError.Rejected("HSD compilation failed: ${e.message}")
        }
    }

    private fun textToHsd(text: String): ByteArray {
        val document = buildJsonObject {
            put("scene", buildJsonObject {
                put("children", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("x", 0)
                        put("y", 0)
                        put("text", text)
                        put("color", "#FFFFFF")
                    })
                })
            })
        }
        return json.encodeToString(JsonObject.serializer(), document).toByteArray(Charsets.UTF_8)
    }

    override suspend fun textOutput(request: TextOutputRequest): TextOutputResult {
        throw SensesError.Unavailable("Halo endpoint does not support text output (use VisualOutput with HSD/HRP)")
    }

    override suspend fun textInput(request: TextInputRequest): TextInputResult {
        throw SensesError.Unavailable("Halo endpoint does not support text input (no keyboard)")
    }

    override suspend fun interactionInput(request: InteractionInputRequest): InteractionInputResult {
        ensureReady()
        val opId = nextOpId()
        val startedAt = System.currentTimeMillis()

        val timeout = effectiveTimeout(request.timeoutMillis, 60_000)
        val event = try {
            withTimeout(timeout.milliseconds) {
                waitForInteraction(request)
            }
        } catch (e: TimeoutCancellationException) {
            throw SensesError.Timeout("interaction input timed out")
        } catch (e: CancellationException) {
            throw e
        }

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

    private tailrec suspend fun waitForInteraction(request: InteractionInputRequest): InteractionEvent {
        pollInteraction(request)?.let { return it }
        delay(50)
        return waitForInteraction(request)
    }

    private fun pollInteraction(request: InteractionInputRequest): InteractionEvent? {
        val iterator = interactionQueue.iterator()
        while (iterator.hasNext()) {
            val event = iterator.next()
            if (request.acceptedGestures.isEmpty() || InteractionFilter(event, request)) {
                iterator.remove()
                return event
            }
        }
        return null
    }

    override suspend fun statusInput(request: StatusInputRequest): StatusInputResult {
        ensureReady()
        val opId = nextOpId()
        val startedAt = System.currentTimeMillis()

        val timeout = effectiveTimeout(request.timeoutMillis, 10_000)
        val payload = try {
            session.requestResponse(
                requestCode = HaloProtocol.DEVICE_STATUS,
                requestPayload = byteArrayOf(),
                responseCode = HaloProtocol.DEVICE_STATUS,
                timeout = timeout.milliseconds,
            )
        } catch (e: HaloTransportException) {
            _state.value = EndpointState.DISCONNECTED
            throw SensesError.Disconnected(e.message ?: "transport disconnected")
        } catch (e: TimeoutCancellationException) {
            throw SensesError.Timeout("status input timed out")
        }

        val status = if (payload.size >= 4) {
            EndpointStatus(
                batteryLevel = payload[0].toInt() and 0xFF,
                batteryVoltage = ((payload[1].toInt() and 0xFF) shl 8) or (payload[2].toInt() and 0xFF),
                batteryCharging = (payload[3].toInt() and 0xFF) != 0,
            )
        } else {
            EndpointStatus()
        }

        val completedAt = System.currentTimeMillis()
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

    private suspend fun handleMessage(msg: HaloMessage) {
        when (msg.code) {
            HaloProtocol.TAP -> {
                val gesture = when (msg.payload.getOrElse(0) { 0 }.toInt()) {
                    1 -> TapGesture.SINGLE
                    2 -> TapGesture.DOUBLE
                    3 -> TapGesture.TRIPLE
                    else -> return
                }
                val event = InteractionEvent.Tap(gesture)
                _recordedInteractions.add(event)
                interactionQueue.add(event)
                config.onInteraction(event)
            }
            HaloProtocol.BUTTON -> {
                val gesture = when (msg.payload.getOrElse(0) { 0 }.toInt()) {
                    1 -> ButtonGesture.SINGLE
                    2 -> ButtonGesture.DOUBLE
                    3 -> ButtonGesture.LONG
                    else -> return
                }
                val event = InteractionEvent.Button(gesture)
                _recordedInteractions.add(event)
                interactionQueue.add(event)
                config.onInteraction(event)
            }
            else -> { /* HaloSession or other consumers handle these. */ }
        }
    }

    private fun effectiveTimeout(requested: Long, default: Long): Long =
        minOf(if (requested > 0) requested else default, config.operationTimeout.inWholeMilliseconds)

    /** Get the current framebuffer snapshot (for test assertions). */
    fun framebufferSnapshot(): IntArray = renderer.snapshot()

    /** Close the endpoint scope. Call when the endpoint is unbound. */
    fun close() {
        scope.cancel()
    }
}
