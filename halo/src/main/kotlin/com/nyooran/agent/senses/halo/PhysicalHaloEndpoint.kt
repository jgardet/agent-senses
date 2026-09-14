package com.nyooran.agent.senses.halo

import com.nyooran.agent.senses.*
import com.nyooran.agent.senses.audio.PcmToWav
import com.nyooran.agent.senses.audio.WavReader
import halo.engine.HaloCommands
import halo.engine.HaloDeviceException
import halo.engine.HaloLimitException
import halo.engine.HaloProtocol
import halo.engine.HaloSession
import halo.engine.HaloMessage
import halo.engine.HaloTransportException
import halo.engine.HsdHrpCompiler
import halo.engine.HsdText
import halo.engine.HaloBleTransport
import halo.engine.SpritePacker
import halo.engine.StubSpritePacker
import halo.engine.display.HrpFailure
import halo.engine.display.HrpRenderer
import java.io.ByteArrayInputStream
import java.util.TimeZone
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
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
        /** Push the host clock/timezone to `frame.time` after the runtime installs. */
        val syncTimeOnConnect: Boolean = true,
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

    /**
     * Capability tokens parsed from the runtime STATUS message
     * (`HRP1;primitives,...;fw=..;eui=..`). Null until the runtime reports in.
     */
    var runtimeCapabilities: Set<String>? = null
        private set

    /** Firmware version reported by the runtime (`frame.FIRMWARE_VERSION`). */
    var firmwareVersion: String? = null
        private set

    /** Device EUI reported by the runtime (`frame.get_eui()`). */
    var deviceEui: String? = null
        private set

    private val _recordedHrp = mutableListOf<ByteArray>()
    val recordedHrp: List<ByteArray> get() = _recordedHrp.toList()

    private fun nextOpId() = "halo-op-${++operationCounter}"

    private val profileBase = SenseProfile(
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

    override val profile: SenseProfile get() = profileBase.copy(state = _state.value)

    override suspend fun connect() {
        if (_state.value == EndpointState.READY) return

        messageJob = scope.launch { transport.messages.collect { handleMessage(it) } }

        withTimeout(config.connectionTimeout) {
            transport.connect()
        }

        // Subscribe for the runtime's boot STATUS before installing so the
        // announcement emitted during `require` is not missed. The install
        // itself can take seconds on real hardware, so this collector is
        // always live by the time the runtime announces itself.
        val statusWait = scope.async {
            transport.messages.first { msg ->
                msg.code == HaloProtocol.STATUS &&
                    msg.payload.size >= 3 &&
                    msg.payload[0] == 'H'.code.toByte() &&
                    msg.payload[1] == 'R'.code.toByte() &&
                    msg.payload[2] == 'P'.code.toByte()
            }
        }

        config.runtimeInstaller(transport)

        // Capture negotiated capabilities and firmware metadata before
        // reporting READY; a runtime that never announces just times out.
        runCatching {
            withTimeout(2_000) { parseRuntimeStatus(statusWait.await().payload) }
        }
        statusWait.cancel()

        if (config.syncTimeOnConnect) {
            runCatching {
                transport.sendMessage(
                    HaloProtocol.SET_TIME,
                    HaloCommands.setTime(System.currentTimeMillis() / 1000, currentZoneOffset()),
                )
            }
        }

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

        // Optional capture overrides; LC3 needs a host-side decoder we don't ship.
        val encoder = (request.deviceOptions["encoder"] as? String) ?: "pcm"
        if (!encoder.equals("pcm", ignoreCase = true)) {
            throw SensesError.Rejected("Halo mic encoder '$encoder' is not supported by the host (only pcm)")
        }
        val sampleRate = (request.deviceOptions["sampleRate"] as? Int)
            ?: config.audioFormat.sampleRate
        val bitDepth = (request.deviceOptions["bitDepth"] as? Int)
            ?: config.audioFormat.bitDepth
        val channels = (request.deviceOptions["channels"] as? Int)
            ?: config.audioFormat.channels
        val format = config.audioFormat.copy(
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            channels = channels,
        )

        val startPayload = HaloCommands.microphoneStart(
            gain = request.gain,
            aec = request.aec,
            voice = request.voice,
            encoder = "pcm",
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            channels = channels,
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
        } catch (e: HaloDeviceException) {
            throw SensesError.Protocol("device error: ${e.message}")
        } catch (e: HaloTransportException) {
            _state.value = EndpointState.DISCONNECTED
            throw SensesError.Disconnected(e.message ?: "transport disconnected")
        } catch (e: TimeoutCancellationException) {
            throw SensesError.Timeout("audio input timed out")
        }

        val wav = PcmToWav.fromPcm16(
            pcm,
            format.sampleRate,
            format.channels,
            format.bitDepth,
        )
        val durationMillis = if (pcm.isEmpty()) 0L else {
            pcm.size * 1000L / (format.sampleRate * format.channels * (format.bitDepth / 8))
        }

        val completedAt = System.currentTimeMillis()
        return AudioInputResult(
            audio = wav,
            format = format,
            durationMillis = durationMillis,
            provenance = Provenance(
                operationId = opId,
                endpointId = config.endpointId,
                backendName = "Halo",
                backendKind = BackendKind.PHYSICAL,
                capability = SenseCapability.AudioInput,
                origin = ResultOrigin.MICROPHONE,
                mediaFormat = MediaFormat(
                    encoding = format.encoding,
                    mime = format.mime,
                    durationMillis = durationMillis,
                    sampleRate = format.sampleRate,
                    channels = format.channels,
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
        val encoder = (request.deviceOptions["encoder"] as? String) ?: "pcm"
        if (!encoder.equals("pcm", ignoreCase = true)) {
            throw SensesError.Rejected("Halo speaker encoder '$encoder' is not supported by the host (only pcm)")
        }

        val startPayload = HaloCommands.speakerStart(
            encoder = "pcm",
            sampleRate = targetSampleRate,
            bitDepth = targetBitDepth,
            channels = targetChannels,
            volume = volume,
            gain = (request.deviceOptions["gain"] as? Int) ?: 0,
            budget = (request.deviceOptions["budget"] as? Int) ?: 0,
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

        // Optional on-device mpix pipeline ops (see HaloCommands.MpixOp).
        // Crop/resize shrink the JPEG before the slow BLE transfer; denoise
        // and convolution kernels improve what the agent sees.
        val mpixOps = mutableListOf<HaloCommands.MpixOp>()
        parseIntList(request.deviceOptions["crop"], 4, "crop")?.let { (x, y, w, h) ->
            mpixOps.add(HaloCommands.MpixOp.Crop(x, y, w, h))
        }
        parseIntList(request.deviceOptions["resize"], 2, "resize")?.let { (w, h) ->
            mpixOps.add(HaloCommands.MpixOp.ResizeSubsample(w, h))
        }
        when (request.deviceOptions["denoise"]) {
            "5x5" -> mpixOps.add(HaloCommands.MpixOp.Denoise5x5)
            null -> Unit
            else -> mpixOps.add(HaloCommands.MpixOp.Denoise3x3)
        }
        (request.deviceOptions["kernel"] as? String)?.let { name ->
            val kernel = when (name.lowercase()) {
                "edge_detect" -> HaloCommands.MpixOp.Kernel.EDGE_DETECT
                "gaussian_blur" -> HaloCommands.MpixOp.Kernel.GAUSSIAN_BLUR
                "identity" -> HaloCommands.MpixOp.Kernel.IDENTITY
                "sharpen" -> HaloCommands.MpixOp.Kernel.SHARPEN
                else -> throw SensesError.Rejected("unknown mpix kernel: $name")
            }
            when (request.deviceOptions["kernelSize"] as? String) {
                "5x5" -> mpixOps.add(HaloCommands.MpixOp.Convolve5x5(kernel))
                else -> mpixOps.add(HaloCommands.MpixOp.Convolve3x3(kernel))
            }
        }
        (request.deviceOptions["jpegQuality"] as? Int)?.let {
            mpixOps.add(HaloCommands.MpixOp.JpegQuality(it))
        }
        if (mpixOps.isNotEmpty()) {
            requireRuntimeCapability("mpix")
        }

        val startPayload = HaloCommands.capturePhoto(
            qualityIndex = qualityIndex,
            halfResolution = 320,
            panShifted = 140,
            raw = false,
            ops = mpixOps,
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
        // Report the post-pipeline dimensions when a resize/crop op set them.
        val outDims = mpixOps.filterIsInstance<HaloCommands.MpixOp.ResizeSubsample>().lastOrNull()?.let { it.w to it.h }
            ?: mpixOps.filterIsInstance<HaloCommands.MpixOp.Crop>().lastOrNull()?.let { it.w to it.h }
        val outFormat = outDims?.let { config.imageFormat.copy(width = it.first, height = it.second) }
            ?: config.imageFormat
        return ImageInputResult(
            image = image,
            format = outFormat,
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
                    width = outFormat.width,
                    height = outFormat.height,
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

    private fun textToHsd(text: String): ByteArray =
        json.encodeToString(JsonObject.serializer(), HsdText.document(text)).toByteArray(Charsets.UTF_8)

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
        } catch (e: HaloDeviceException) {
            throw SensesError.Protocol("device error: ${e.message}")
        } catch (e: HaloTransportException) {
            _state.value = EndpointState.DISCONNECTED
            throw SensesError.Disconnected(e.message ?: "transport disconnected")
        } catch (e: TimeoutCancellationException) {
            throw SensesError.Timeout("status input timed out")
        }

        val extras = buildMap {
            firmwareVersion?.let { put("firmware", it) }
            deviceEui?.let { put("eui", it) }
            runtimeCapabilities?.let { put("runtime", it.sorted().joinToString(",")) }
        }
        val status = if (payload.size >= 4) {
            EndpointStatus(
                batteryLevel = payload[0].toInt() and 0xFF,
                batteryVoltage = ((payload[1].toInt() and 0xFF) shl 8) or (payload[2].toInt() and 0xFF),
                batteryCharging = (payload[3].toInt() and 0xFF) != 0,
                extras = extras,
            )
        } else {
            EndpointStatus(extras = extras)
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

    // ------------------------------------------------------------------
    // Device-specific extensions (not part of the generic SenseEndpoint
    // contract). These map to the `he_runtime.lua` v3 message codes; callers
    // that go through the generic contract never see them.
    // ------------------------------------------------------------------

    /**
     * Play a firmware-synthesized sound preset — cheap acknowledgement that
     * needs no audio streaming. [name] must be one of the firmware presets
     * (`pickup`, `laser`, `explosion`, `powerup`, `hit`, `jump`, `blip`).
     */
    suspend fun playSound(
        name: String,
        volume: Int? = null,
        durationMillis: Int? = null,
        seed: Int? = null,
    ) {
        ensureReady()
        requireRuntimeCapability("sound")
        sendDeviceMessage(
            HaloProtocol.SOUND_PLAY,
            HaloCommands.soundPlay(name, volume, durationMillis, seed),
        )
    }

    /** Put the display panel in/out of power save without clearing content. */
    suspend fun setDisplayPowerSave(enabled: Boolean) {
        ensureReady()
        requireRuntimeCapability("system")
        sendDeviceMessage(
            HaloProtocol.SYSTEM,
            HaloCommands.system(
                if (enabled) HaloProtocol.SYS_DISPLAY_SLEEP else HaloProtocol.SYS_DISPLAY_WAKE,
            ),
        )
    }

    /** Toggle the camera sensor's power-save mode. */
    suspend fun setCameraPowerSave(enabled: Boolean) {
        ensureReady()
        requireRuntimeCapability("system")
        sendDeviceMessage(
            HaloProtocol.SYSTEM,
            HaloCommands.systemFlag(HaloProtocol.SYS_CAMERA_POWER_SAVE, enabled),
        )
    }

    /** Enter standby — BLE stays connected, the runtime resumes in place. */
    suspend fun standby() {
        ensureReady()
        requireRuntimeCapability("system")
        sendDeviceMessage(HaloProtocol.SYSTEM, HaloCommands.system(HaloProtocol.SYS_STANDBY))
    }

    /** Enter light sleep; [seconds] of 0 sleeps until an interrupt/wake source. */
    suspend fun lightSleep(seconds: Int = 0) {
        ensureReady()
        requireRuntimeCapability("system")
        sendDeviceMessage(HaloProtocol.SYSTEM, HaloCommands.systemSeconds(HaloProtocol.SYS_LIGHT_SLEEP, seconds))
    }

    /** Prevent the firmware's auto-sleep while connected. */
    suspend fun stayAwake(enabled: Boolean) {
        ensureReady()
        requireRuntimeCapability("system")
        sendDeviceMessage(HaloProtocol.SYSTEM, HaloCommands.systemFlag(HaloProtocol.SYS_STAY_AWAKE, enabled))
    }

    /** Ship mode — deepest sleep; the device only wakes on charge. */
    suspend fun shipMode() {
        ensureReady()
        requireRuntimeCapability("system")
        sendDeviceMessage(HaloProtocol.SYSTEM, HaloCommands.system(HaloProtocol.SYS_SHIP_MODE))
    }

    /** Enable/disable the charging circuit. */
    suspend fun setCharging(enabled: Boolean) {
        ensureReady()
        requireRuntimeCapability("system")
        sendDeviceMessage(HaloProtocol.SYSTEM, HaloCommands.systemFlag(HaloProtocol.SYS_CHARGE, enabled))
    }

    /** Deep sleep for [seconds]; 0 sleeps until wake source. */
    suspend fun deepSleep(seconds: Int = 0) {
        ensureReady()
        requireRuntimeCapability("system")
        sendDeviceMessage(HaloProtocol.SYSTEM, HaloCommands.systemSeconds(HaloProtocol.SYS_DEEP_SLEEP, seconds))
    }

    /**
     * Push the host clock into `frame.time`. [zone] defaults to the host's
     * current `±hh:mm` offset.
     */
    suspend fun syncTime(epochMillis: Long = System.currentTimeMillis(), zone: String? = null) {
        ensureReady()
        val zoneStr = zone ?: currentZoneOffset()
        sendDeviceMessage(
            HaloProtocol.SET_TIME,
            HaloCommands.setTime(epochMillis / 1000, zoneStr),
        )
    }

    /**
     * Request an IMU snapshot: pitch/roll (radians), compass (µT), accel (mg).
     * Fields are null when the runtime reports fewer values than expected.
     */
    suspend fun readImu(timeoutMillis: Long = 5_000): HaloImuSnapshot {
        ensureReady()
        requireRuntimeCapability("imu")
        val payload = try {
            session.requestResponse(
                requestCode = HaloProtocol.IMU_READ,
                requestPayload = byteArrayOf(),
                responseCode = HaloProtocol.IMU,
                timeout = timeoutMillis.milliseconds,
            )
        } catch (e: HaloDeviceException) {
            throw SensesError.Protocol("device error: ${e.message}")
        } catch (e: HaloTransportException) {
            _state.value = EndpointState.DISCONNECTED
            throw SensesError.Disconnected(e.message ?: "transport disconnected")
        } catch (e: TimeoutCancellationException) {
            throw SensesError.Timeout("IMU read timed out")
        }
        return HaloImuSnapshot.parse(payload.toString(Charsets.UTF_8))
    }

    /**
     * Tune the hardware tap detector. Null fields are left unchanged.
     * [mode] is `sensitive`, `normal`, or `robust`; [axis] is `x`, `y`, or `z`.
     */
    suspend fun configureTap(
        mode: String? = null,
        axis: String? = null,
        threshold: Int? = null,
        gestureDurationMillis: Int? = null,
        waitForTimeout: Boolean? = null,
    ) {
        ensureReady()
        requireRuntimeCapability("tap")
        sendDeviceMessage(
            HaloProtocol.TAP_CONFIG,
            HaloCommands.tapConfig(mode, axis, threshold, gestureDurationMillis, waitForTimeout),
        )
    }

    private suspend fun sendDeviceMessage(code: Int, payload: ByteArray) {
        try {
            transport.sendMessage(code, payload)
        } catch (e: HaloTransportException) {
            _state.value = EndpointState.DISCONNECTED
            throw SensesError.Disconnected(e.message ?: "transport disconnected")
        }
    }

    private fun currentZoneOffset(): String {
        val offsetMillis = TimeZone.getDefault().getOffset(System.currentTimeMillis())
        val sign = if (offsetMillis < 0) "-" else "+"
        val total = kotlin.math.abs(offsetMillis) / 60_000
        return "%s%02d:%02d".format(sign, total / 60, total % 60)
    }

    /**
     * Parse the runtime STATUS payload (`HRP1;cap,cap;fw=x;eui=y`) into
     * negotiated capabilities and device metadata. Non-string or legacy
     * payloads are ignored.
     */
    private fun parseRuntimeStatus(payload: ByteArray) {
        val text = runCatching { payload.toString(Charsets.UTF_8) }.getOrNull() ?: return
        if (!text.startsWith("HRP")) return
        val tokens = text.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        val caps = mutableSetOf<String>()
        for (token in tokens.drop(1)) {
            when {
                token.startsWith("fw=") -> firmwareVersion = token.removePrefix("fw=")
                token.startsWith("eui=") -> deviceEui = token.removePrefix("eui=")
                else -> caps.addAll(token.split(',').map { it.trim() }.filter { it.isNotEmpty() })
            }
        }
        caps.add(tokens[0])
        runtimeCapabilities = caps
    }

    private fun requireRuntimeCapability(token: String) {
        val caps = runtimeCapabilities ?: return
        if (token !in caps) {
            throw SensesError.Unavailable("Halo runtime does not advertise '$token'")
        }
    }

    private suspend fun handleMessage(msg: HaloMessage) {
        when (msg.code) {
            HaloProtocol.STATUS -> parseRuntimeStatus(msg.payload)
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

    /** Parse a `deviceOptions` entry as a fixed-size list of ints. */
    private fun parseIntList(value: Any?, expectedSize: Int, name: String): List<Int>? {
        if (value == null) return null
        val list = when (value) {
            is IntArray -> value.toList()
            is List<*> -> value.map { (it as? Number)?.toInt()
                ?: throw SensesError.Rejected("$name entries must be integers") }
            else -> throw SensesError.Rejected("$name must be a list of $expectedSize integers")
        }
        if (list.size != expectedSize) {
            throw SensesError.Rejected("$name must contain $expectedSize integers, got ${list.size}")
        }
        return list
    }

    /** Get the current framebuffer snapshot (for test assertions). */
    fun framebufferSnapshot(): IntArray = renderer.snapshot()

    /** Close the endpoint scope. Call when the endpoint is unbound. */
    fun close() {
        scope.cancel()
    }
}

/**
 * IMU snapshot from `frame.imu` — pitch/roll in degrees, compass in µT,
 * accelerometer in mg. Fields are null when the firmware reported no value.
 */
data class HaloImuSnapshot(
    val pitchDegrees: Float?,
    val rollDegrees: Float?,
    val compassX: Float?,
    val compassY: Float?,
    val compassZ: Float?,
    val accelX: Float?,
    val accelY: Float?,
    val accelZ: Float?,
) {
    companion object {
        /** Parse `pitch;roll;cx;cy;cz;ax;ay;az` emitted by `he_runtime.lua`. */
        fun parse(payload: String): HaloImuSnapshot {
            val fields = payload.split(';').map { it.trim().toFloatOrNull() }
            fun at(i: Int) = fields.getOrNull(i)
            return HaloImuSnapshot(
                at(0), at(1), at(2), at(3), at(4), at(5), at(6), at(7),
            )
        }
    }
}
