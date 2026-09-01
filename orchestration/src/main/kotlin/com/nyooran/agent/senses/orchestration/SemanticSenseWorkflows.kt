package com.nyooran.agent.senses.orchestration

import com.nyooran.agent.senses.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Production semantic sense workflow orchestrator (AgentSensesService).
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
        request: AudioInputRequest = AudioInputRequest(maxDurationMillis = 5_000, maxBytes = 65_536),
        keepRaw: Boolean = false,
    ): Result<SemanticListenResult> = cancellationAware {
        val model = transcriptionModel
            ?: throw SensesError.ModelUnavailable("transcription model not configured")
        val endpoint = resolve(endpointId, SenseCapability.AudioInput)
        val audioResult = endpoint.audioInput(request)
        val transcription = model.transcribe(audioResult.audio, audioResult.format)
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
        SemanticListenResult(
            transcript = transcription.transcript,
            confidence = transcription.confidence,
            language = transcription.language,
            rawAudioProvenance = audioResult.provenance,
            transcriptionProvenance = transcriptionProvenance,
            rawAudio = if (keepRaw) audioResult.audio else null,
            rawAudioFormat = audioResult.format,
        )
    }

    /**
     * Look at the environment via an endpoint, then observe the image.
     * Returns description with both raw image and observation provenance.
     */
    suspend fun lookAndObserve(
        endpointId: EndpointId? = null,
        request: ImageInputRequest = ImageInputRequest(resolution = 640, maxBytes = 65_536),
        prompt: String? = null,
        keepRaw: Boolean = false,
    ): Result<SemanticLookResult> = cancellationAware {
        val model = visionModel
            ?: throw SensesError.ModelUnavailable("vision model not configured")
        val endpoint = resolve(endpointId, SenseCapability.ImageInput)
        val imageResult = endpoint.imageInput(request)
        val observation = model.observe(imageResult.image, imageResult.format, prompt)
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
        SemanticLookResult(
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
    ): Result<SemanticSpeakResult> = cancellationAware {
        val model = ttsModel
            ?: throw SensesError.ModelUnavailable("TTS model not configured")
        val ttsResult = model.synthesize(text)
        val ttsProvenance = Provenance(
            operationId = "tts-${System.currentTimeMillis()}",
            endpointId = EndpointId("model"),
            backendName = "TtsModel",
            backendKind = BackendKind.MODEL,
            capability = SenseCapability.AudioOutput,
            origin = ResultOrigin.MODEL,
            transformations = listOf(Transformation.TTS_SYNTHESIS),
            startedAt = System.currentTimeMillis(),
            completedAt = System.currentTimeMillis(),
        )
        val endpoint = resolve(endpointId, SenseCapability.AudioOutput)
        val outputResult = endpoint.audioOutput(AudioOutputRequest(
            audio = ttsResult.audio,
            format = ttsResult.format,
        ))
        SemanticSpeakResult(
            ttsProvenance = ttsProvenance,
            outputProvenance = outputResult.provenance,
        )
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
        val result = endpoint.visualOutput(VisualOutputRequest(content = content))
        SemanticPresentResult(outputProvenance = result.provenance)
    }

    /**
     * Multi-modal turn: listen, look, and produce both transcript and
     * observation. Returns partial results when one modality fails.
     */
    suspend fun multiModalTurn(
        endpointId: EndpointId? = null,
        visionPrompt: String? = null,
        keepRaw: Boolean = false,
    ): MultiModalResult = coroutineScope {
        val listenDeferred = async { listenAndTranscribe(endpointId = endpointId, keepRaw = keepRaw) }
        val lookDeferred = async { lookAndObserve(endpointId = endpointId, prompt = visionPrompt, keepRaw = keepRaw) }
        val listen = listenDeferred.await()
        val look = lookDeferred.await()
        MultiModalResult(
            listen = listen.getOrNull(),
            listenFailure = listen.exceptionOrNull()?.toFailure(),
            look = look.getOrNull(),
            lookFailure = look.exceptionOrNull()?.toFailure(),
        )
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
    private inline fun <T> cancellationAware(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
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
