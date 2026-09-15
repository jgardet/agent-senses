package com.nyooran.agent.senses.orchestration

import com.nyooran.agent.senses.*
import com.nyooran.agent.senses.audio.PcmToWav
import com.nyooran.agent.senses.audio.WavReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope

/**
 * Production semantic sense workflow orchestrator.
 *
 * This is the app-facing orchestration layer defined in
 * AGENT_SENSES_ARCHITECTURE.md (AD-6). It composes raw endpoint
 * capabilities with model and platform adapters:
 *
 * - [listenAndTranscribe]: audio capture → normalization → transcription
 * - [lookAndObserve]: image capture → normalization → vision observation
 * - [speakText]: text → TTS synthesis → audio output through selected endpoint
 * - [presentSemantic]: semantic content → endpoint-appropriate presentation
 *
 * The endpoint-facing layer captures or emits bounded media, content,
 * state, and events. It does not transcribe audio, analyze images,
 * synthesize language, choose tools, or invent fallbacks. Those
 * transformations live here, in the orchestration layer, behind
 * model contracts ([TranscriptionModel], [VisionModel], [TtsModel]).
 *
 * Preserves raw observation and transformation provenance.
 * Keeps raw media ephemeral by default (only returned when keepRaw = true).
 * Returns partial results when one requested output modality fails.
 */
class SemanticSenseWorkflows(
    private val registry: SenseEndpointRegistry,
    private val transcriptionModel: TranscriptionModel? = null,
    private val visionModel: VisionModel? = null,
    private val ttsModel: TtsModel? = null,
) {

    /**
     * Listen on an endpoint, then transcribe the audio.
     * Returns transcript with both raw audio and transcription provenance.
     */
    suspend fun listenAndTranscribe(
        endpointId: EndpointId? = null,
        request: AudioInputRequest = AudioInputRequest(maxDurationMillis = 5_000, maxBytes = 1_048_576),
        keepRaw: Boolean = false,
    ): Result<SemanticListenResult> = cancellationAware {
        val endpoint = resolve(endpointId, SenseCapability.AudioInput)
        withOperation(endpoint, SenseCapability.AudioInput) {
            val audioResult = registry.coordinator.withCapability(endpoint, SenseCapability.AudioInput) {
                endpoint.audioInput(request)
            }
            val transcription = transcribe(audioResult.audio, audioResult.format)
            buildListenResult(audioResult, transcription, keepRaw)
        }
    }

    /**
     * Look at the environment via an endpoint, then observe the image.
     * Returns description with both raw image and observation provenance.
     */
    suspend fun lookAndObserve(
        endpointId: EndpointId? = null,
        request: ImageInputRequest = ImageInputRequest(resolution = 640, maxBytes = 1_048_576),
        prompt: String? = null,
        keepRaw: Boolean = false,
    ): Result<SemanticLookResult> = cancellationAware {
        val endpoint = resolve(endpointId, SenseCapability.ImageInput)
        withOperation(endpoint, SenseCapability.ImageInput) {
            val imageResult = registry.coordinator.withCapability(endpoint, SenseCapability.ImageInput) {
                endpoint.imageInput(request)
            }
            val observation = observe(imageResult.image, imageResult.format, prompt)
            buildLookResult(imageResult, observation, keepRaw)
        }
    }

    /**
     * Synthesize speech from text, then play it through an endpoint.
     * Returns TTS and output provenance.
     *
     * This is the semantic `sense_say` workflow: text → TTS → endpoint
     * audio output. The lower-level [/v1/sense/speak] route handles
     * raw audio playback; this workflow adds the TTS transformation.
     */
    suspend fun speakText(
        text: String,
        endpointId: EndpointId? = null,
        deviceOptions: Map<String, Any> = emptyMap(),
        volume: Int = 80,
    ): Result<SemanticSpeakResult> = cancellationAware {
        val endpoint = resolve(endpointId, SenseCapability.AudioOutput)
        withOperation(endpoint, SenseCapability.AudioOutput) {
            // Sentence-level pipelining: synthesize each chunk while the
            // previous one streams to the speaker, so synthesis of a
            // multi-sentence reply no longer adds fully to first-byte
            // latency. Single-chunk text takes the same path as before.
            val chunks = splitSpeechChunks(text)
            val ttsStartedAt = System.currentTimeMillis()
            val pcm = ByteArrayOutputStream()
            var outFormat: AudioFormat? = null
            var firstOutput: AudioOutputResult? = null
            var lastOutput: AudioOutputResult? = null

            supervisorScope {
                var pending: Deferred<TtsResult>? = async { synthesize(chunks.first()) }
                for ((index, chunk) in chunks.withIndex()) {
                    val ttsResult = pending!!.await()
                    pending = chunks.getOrNull(index + 1)
                        ?.let { next -> async { synthesize(next) } }
                    val outputResult = registry.coordinator.withCapability(endpoint, SenseCapability.AudioOutput) {
                        endpoint.audioOutput(AudioOutputRequest(
                            audio = ttsResult.audio,
                            format = ttsResult.format,
                            volume = volume,
                            deviceOptions = deviceOptions,
                        ))
                    }
                    if (firstOutput == null) firstOutput = outputResult
                    lastOutput = outputResult
                    outFormat = ttsResult.format
                    appendPcm(pcm, ttsResult)
                }
            }

            val ttsProvenance = Provenance(
                operationId = "tts-${ttsStartedAt}",
                endpointId = EndpointId("model"),
                backendName = "TtsModel",
                backendKind = BackendKind.MODEL,
                capability = SenseCapability.AudioOutput,
                origin = ResultOrigin.MODEL,
                transformations = listOf(Transformation.TTS_SYNTHESIS),
                startedAt = ttsStartedAt,
                completedAt = System.currentTimeMillis(),
            )
            val mergedAudio = outFormat?.let { format ->
                PcmToWav.fromPcm16(
                    pcm.toByteArray(),
                    format.sampleRate,
                    format.channels,
                    format.bitDepth,
                )
            }
            SemanticSpeakResult(
                ttsProvenance = ttsProvenance,
                outputProvenance = lastOutput!!.provenance.copy(
                    startedAt = firstOutput!!.provenance.startedAt,
                ),
                audio = mergedAudio,
                format = outFormat,
            )
        }
    }

    /**
     * Split [text] into speakable chunks on sentence boundaries. Fragments
     * shorter than [MIN_SPEECH_CHUNK] merge into the previous chunk so
     * playback stays natural; the result is capped at [MAX_SPEECH_CHUNKS]
     * to bound per-call synthesis overhead.
     */
    private fun splitSpeechChunks(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.length <= MIN_SPEECH_CHUNK) return listOf(trimmed)
        val parts = trimmed.split(Regex("(?<=[.!?…])\\s+|\\n+"))
            .filter { it.isNotBlank() }
        if (parts.size <= 1) return listOf(trimmed)
        val chunks = mutableListOf<String>()
        for (part in parts) {
            if (chunks.isNotEmpty() &&
                (chunks.last().length < MIN_SPEECH_CHUNK || chunks.size >= MAX_SPEECH_CHUNKS)) {
                chunks[chunks.lastIndex] = "${chunks.last()} $part"
            } else {
                chunks.add(part)
            }
        }
        return chunks
    }

    private companion object {
        /** Chunks shorter than this merge into the previous sentence. */
        const val MIN_SPEECH_CHUNK = 24
        /** Upper bound on per-utterance synthesis calls. */
        const val MAX_SPEECH_CHUNKS = 12
    }

    /** Append the PCM payload of a TTS result to [out] (WAV header stripped). */
    private suspend fun appendPcm(out: ByteArrayOutputStream, result: TtsResult) {
        val isRiff = result.audio.size >= 4 &&
            result.audio.copyOfRange(0, 4).contentEquals("RIFF".toByteArray())
        if (!isRiff || !result.format.encoding.equals("wav", ignoreCase = true)) {
            out.write(result.audio)
            return
        }
        WavReader.readPcm(
            ByteArrayInputStream(result.audio),
            result.format.sampleRate,
        ).collect { frame -> out.write(frame) }
    }

    /**
     * Present semantic content on an endpoint.
     * The content kind is determined by what the endpoint supports.
     */
    suspend fun presentSemantic(
        content: VisualContent,
        endpointId: EndpointId? = null,
    ): Result<SemanticPresentResult> = cancellationAware {
        val endpoint = resolve(endpointId, SenseCapability.VisualOutput)
        withOperation(endpoint, SenseCapability.VisualOutput) {
            val result = registry.coordinator.withCapability(endpoint, SenseCapability.VisualOutput) {
                endpoint.visualOutput(VisualOutputRequest(content = content))
            }
            SemanticPresentResult(outputProvenance = result.provenance)
        }
    }

    /**
     * Wait for a user interaction on an endpoint.
     * Returns the typed event (tap, button, approval, selection, text
     * entry, or disconnection) with input provenance.
     */
    suspend fun waitForInteraction(
        endpointId: EndpointId? = null,
        request: InteractionInputRequest = InteractionInputRequest(),
    ): Result<SemanticWaitResult> = cancellationAware {
        val endpoint = resolve(endpointId, SenseCapability.InteractionInput)
        withOperation(endpoint, SenseCapability.InteractionInput) {
            val result = registry.coordinator.withCapability(endpoint, SenseCapability.InteractionInput) {
                endpoint.interactionInput(request)
            }
            SemanticWaitResult(event = result.event, inputProvenance = result.provenance)
        }
    }

    /**
     * Resolve a single endpoint that supports all of [capabilities].
     * Follows the same selection rules as [resolve]: an explicit
     * [endpointId] is honored, otherwise exactly one eligible endpoint
     * must exist or the call fails with [SensesError].
     */
    fun endpointFor(endpointId: EndpointId?, capabilities: Set<SenseCapability>): SenseEndpoint =
        resolveBoth(endpointId, capabilities)

    /**
     * Multi-modal turn: listen, look, and produce both transcript and
     * observation. Returns partial results when one modality fails.
     *
     * Endpoint resource locks are acquired before any I/O starts. The
     * endpoint's [ConcurrencyProfile] decides whether listen and look may
     * overlap. Model inferences run after the locks are released so that
     * Gemma work does not block other endpoint operations.
     */
    suspend fun multiModalTurn(
        endpointId: EndpointId? = null,
        visionPrompt: String? = null,
        keepRaw: Boolean = false,
    ): MultiModalResult {
        val endpoint = resolveBoth(
            endpointId,
            setOf(SenseCapability.AudioInput, SenseCapability.ImageInput),
        )
        return withOperation(endpoint, SenseCapability.AudioInput) {
            val canOverlap = endpoint.profile.canOverlap(
                SenseCapability.AudioInput,
                SenseCapability.ImageInput,
            )
            val (audioCapture, imageCapture) = registry.coordinator.withCombinedCapabilities(
                endpoint,
                setOf(SenseCapability.AudioInput, SenseCapability.ImageInput),
            ) {
                val audioRequest = AudioInputRequest(
                    maxDurationMillis = 5_000,
                    maxBytes = 65_536,
                )
                val imageRequest = ImageInputRequest(
                    resolution = 640,
                    maxBytes = 65_536,
                )
                if (canOverlap) {
                    supervisorScope {
                        val audio = async { cancellationAware { endpoint.audioInput(audioRequest) } }
                        val image = async { cancellationAware { endpoint.imageInput(imageRequest) } }
                        audio.await() to image.await()
                    }
                } else {
                    val audio = cancellationAware { endpoint.audioInput(audioRequest) }
                    val image = cancellationAware { endpoint.imageInput(imageRequest) }
                    audio to image
                }
            }

            val audioFailure = audioCapture.exceptionOrNull()?.toFailure()
            val imageFailure = imageCapture.exceptionOrNull()?.toFailure()

            val listen = audioCapture.getOrNull()?.let {
                cancellationAware { buildListenResult(it, transcribe(it.audio, it.format), keepRaw) }
            }
            val look = imageCapture.getOrNull()?.let {
                cancellationAware { buildLookResult(it, observe(it.image, it.format, visionPrompt), keepRaw) }
            }

            MultiModalResult(
                listen = listen?.getOrNull(),
                listenFailure = listen?.exceptionOrNull()?.toFailure() ?: audioFailure,
                look = look?.getOrNull(),
                lookFailure = look?.exceptionOrNull()?.toFailure() ?: imageFailure,
            )
        }
    }

    data class MultiModalResult(
        val listen: SemanticListenResult? = null,
        val listenFailure: SenseFailure? = null,
        val look: SemanticLookResult? = null,
        val lookFailure: SenseFailure? = null,
    ) {
        val isComplete get() = listen != null && look != null
        val isPartial get() = (listen != null || look != null) && (listenFailure != null || lookFailure != null)
        val isFailed get() = listen == null && look == null
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Run [block] and wrap non-cancellation failures in a [Result].
     * Coroutine cancellation is always rethrown so callers can cancel cleanly.
     */
    private inline suspend fun <T> cancellationAware(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

    /**
     * Register [block] as an active operation on [endpoint] and complete it
     * in a [finally] block, so unbind/replacement can cancel it.
     */
    private suspend fun <T> withOperation(
        endpoint: SenseEndpoint,
        capability: SenseCapability,
        block: suspend (String) -> T,
    ): T {
        val operationId = registry.nextOperationId()
        val job = coroutineContext[Job]
            ?: error("SemanticSenseWorkflows operations must run within a coroutine Job")
        registry.registerOperation(endpoint.profile.endpointId, operationId, job, capability)
        try {
            return block(operationId)
        } finally {
            registry.completeOperation(endpoint.profile.endpointId, operationId)
        }
    }

    private fun buildListenResult(
        audioResult: AudioInputResult,
        transcription: TranscriptionResult,
        keepRaw: Boolean,
    ): SemanticListenResult {
        val transcriptionProvenance = Provenance(
            operationId = "transcribe-${audioResult.provenance.operationId}",
            endpointId = audioResult.provenance.endpointId,
            backendName = "TranscriptionModel",
            backendKind = BackendKind.MODEL,
            capability = SenseCapability.AudioInput,
            origin = ResultOrigin.MODEL,
            transformations = listOf(Transformation.ASR_TRANSCRIPTION),
            startedAt = audioResult.provenance.startedAt,
            completedAt = System.currentTimeMillis(),
        )
        return SemanticListenResult(
            transcript = transcription.transcript,
            confidence = transcription.confidence,
            language = transcription.language,
            rawAudioProvenance = audioResult.provenance,
            transcriptionProvenance = transcriptionProvenance,
            rawAudio = if (keepRaw) audioResult.audio else null,
            rawAudioFormat = audioResult.format,
        )
    }

    private fun buildLookResult(
        imageResult: ImageInputResult,
        observation: VisionResult,
        keepRaw: Boolean,
    ): SemanticLookResult {
        val observationProvenance = Provenance(
            operationId = "observe-${imageResult.provenance.operationId}",
            endpointId = imageResult.provenance.endpointId,
            backendName = "VisionModel",
            backendKind = BackendKind.MODEL,
            capability = SenseCapability.ImageInput,
            origin = ResultOrigin.MODEL,
            transformations = listOf(Transformation.VISION_ANALYSIS),
            startedAt = imageResult.provenance.startedAt,
            completedAt = System.currentTimeMillis(),
        )
        return SemanticLookResult(
            description = observation.description,
            confidence = observation.confidence,
            objects = observation.objects,
            isRaw = imageResult.isRaw,
            rawImageProvenance = imageResult.provenance,
            observationProvenance = observationProvenance,
            rawImage = if (keepRaw) imageResult.image else null,
            rawImageFormat = imageResult.format,
        )
    }

    private suspend fun transcribe(audio: ByteArray, format: AudioFormat): TranscriptionResult {
        val model = transcriptionModel
            ?: throw SensesError.ModelUnavailable("transcription model not configured")
        return model.transcribe(audio, format)
    }

    private suspend fun observe(image: ByteArray, format: ImageFormat, prompt: String?): VisionResult {
        val model = visionModel
            ?: throw SensesError.ModelUnavailable("vision model not configured")
        return model.observe(image, format, prompt)
    }

    private suspend fun synthesize(text: String): TtsResult {
        val model = ttsModel
            ?: throw SensesError.ModelUnavailable("TTS model not configured")
        return model.synthesize(text)
    }

    private fun resolve(endpointId: EndpointId?, capability: SenseCapability): SenseEndpoint {
        if (endpointId != null) {
            return registry.get(endpointId)
                ?: throw SensesError.Unavailable("endpoint not found: ${endpointId.value}")
        }
        return when (val result = registry.resolve(capability)) {
            is ResolveResult.Resolved -> result.endpoint
            is ResolveResult.Ambiguous -> throw SensesError.Rejected(
                "multiple endpoints support ${capability.name()}: ${result.eligible.map { it.profile.endpointId.value }}",
            )
            is ResolveResult.NoEndpoint -> throw SensesError.Unavailable("no endpoint supports ${capability.name()}")
            is ResolveResult.NotFound -> throw SensesError.Unavailable("endpoint not found: ${result.endpointId.value}")
            is ResolveResult.Unsupported -> throw SensesError.Unavailable(
                "endpoint ${result.endpoint.profile.endpointId.value} does not support ${capability.name()}",
            )
        }
    }

    private fun resolveBoth(endpointId: EndpointId?, capabilities: Set<SenseCapability>): SenseEndpoint {
        if (endpointId != null) {
            val endpoint = registry.get(endpointId)
                ?: throw SensesError.Unavailable("endpoint not found: ${endpointId.value}")
            for (capability in capabilities) {
                if (!endpoint.profile.supports(capability)) {
                    throw SensesError.Unavailable(
                        "endpoint ${endpointId.value} does not support ${capability.name()}",
                    )
                }
            }
            return endpoint
        }
        val first = capabilities.first()
        val eligible = registry.endpointsForCapability(first).filter { endpoint ->
            capabilities.all { endpoint.profile.supports(it) }
        }
        return when {
            eligible.isEmpty() -> throw SensesError.Unavailable(
                "no endpoint supports ${capabilities.map { it.name() }}",
            )
            eligible.size == 1 -> eligible.first()
            else -> throw SensesError.Rejected(
                "multiple endpoints support combined capabilities: ${eligible.map { it.profile.endpointId.value }}",
            )
        }
    }

    private fun SenseCapability.name(): String = when (this) {
        SenseCapability.AudioInput -> "AudioInput"
        SenseCapability.AudioOutput -> "AudioOutput"
        SenseCapability.ImageInput -> "ImageInput"
        SenseCapability.VisualOutput -> "VisualOutput"
        SenseCapability.TextOutput -> "TextOutput"
        SenseCapability.TextInput -> "TextInput"
        SenseCapability.InteractionInput -> "InteractionInput"
        SenseCapability.StatusInput -> "StatusInput"
    }

    private fun Throwable.toFailure(): SenseFailure = when (this) {
        is SensesError -> SenseFailure(
            category = when (category) {
                SensesError.Category.Unavailable -> FailureCategory.UNAVAILABLE
                SensesError.Category.Disconnected -> FailureCategory.DISCONNECTED
                SensesError.Category.Timeout -> FailureCategory.TIMEOUT
                SensesError.Category.Cancelled -> FailureCategory.CANCELLED
                SensesError.Category.Rejected -> FailureCategory.REJECTED
                SensesError.Category.LimitExceeded -> FailureCategory.LIMIT_EXCEEDED
                SensesError.Category.Protocol -> FailureCategory.PROTOCOL
                SensesError.Category.PermissionDenied -> FailureCategory.PERMISSION_DENIED
                SensesError.Category.ModelUnavailable -> FailureCategory.MODEL_UNAVAILABLE
                SensesError.Category.Internal -> FailureCategory.INTERNAL
            },
            message = message ?: "unknown error",
        )
        else -> SenseFailure(
            category = FailureCategory.INTERNAL,
            message = message ?: "unknown error",
        )
    }
}
