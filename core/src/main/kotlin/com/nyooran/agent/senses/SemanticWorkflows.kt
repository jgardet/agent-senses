package com.nyooran.agent.senses

/**
 * Phase 4 A4-04: Semantic sense workflow contracts.
 *
 * These workflows orchestrate raw sense operations with model enrichment:
 * - Audio input → normalization → transcription
 * - Image input → normalization → vision observation
 * - Text → TTS synthesis → audio output through selected endpoint
 * - Semantic output → endpoint-appropriate presentation
 *
 * The workflows preserve raw observation and transformation provenance,
 * keep raw media ephemeral by default, and return partial results when
 * one requested output modality fails.
 *
 * Model interfaces are defined here as contracts; implementations live
 * in the app/simulator modules where the actual models (Gemma, TTS) live.
 */

// ------------------------------------------------------------------ model contracts

/**
 * Speech-to-text model contract.
 * Implementations wrap Gemma ASR or similar.
 */
interface TranscriptionModel {
    suspend fun transcribe(audio: ByteArray, format: AudioFormat): TranscriptionResult
}

data class TranscriptionResult(
    val transcript: String,
    val confidence: Float = 1.0f,
    val language: String? = null,
)

/**
 * Vision observation model contract.
 * Implementations wrap Gemma vision or similar.
 */
interface VisionModel {
    suspend fun observe(image: ByteArray, format: ImageFormat, prompt: String? = null): VisionResult
}

data class VisionResult(
    val description: String,
    val confidence: Float = 1.0f,
    val objects: List<String> = emptyList(),
)

/**
 * Text-to-speech model contract.
 * Implementations wrap Android TTS or similar.
 */
interface TtsModel {
    suspend fun synthesize(text: String, format: AudioFormat = AudioFormat(16000, 16, 1, "wav", "audio/wav")): TtsResult
}

data class TtsResult(
    val audio: ByteArray,
    val format: AudioFormat,
    val durationMillis: Long,
)

// ------------------------------------------------------------------ workflow results

/**
 * Result of a listen-and-transcribe workflow.
 * Preserves both raw audio provenance and transcription provenance.
 */
data class SemanticListenResult(
    val transcript: String,
    val confidence: Float,
    val language: String? = null,
    val rawAudioProvenance: Provenance,
    val transcriptionProvenance: Provenance,
    /** Raw audio is ephemeral by default; only included if keepRaw is true. */
    val rawAudio: ByteArray? = null,
    val rawAudioFormat: AudioFormat? = null,
)

/**
 * Result of a look-and-observe workflow.
 * Preserves both raw image provenance and vision observation provenance.
 */
data class SemanticLookResult(
    val description: String,
    val confidence: Float,
    val objects: List<String>,
    val isRaw: Boolean = false,
    val rawImageProvenance: Provenance,
    val observationProvenance: Provenance,
    /** Raw image is ephemeral by default; only included if keepRaw is true. */
    val rawImage: ByteArray? = null,
    val rawImageFormat: ImageFormat? = null,
)

/**
 * Result of a speak workflow (text → TTS → audio output).
 */
data class SemanticSpeakResult(
    val ttsProvenance: Provenance,
    val outputProvenance: Provenance,
)

/**
 * Result of a present workflow (semantic content → endpoint presentation).
 */
data class SemanticPresentResult(
    val outputProvenance: Provenance,
)

/**
 * Partial result when one modality fails in a multi-modal workflow.
 */
data class PartialResult<T>(
    val success: T? = null,
    val failure: SenseFailure? = null,
) {
    val isComplete get() = success != null && failure == null
    val isPartial get() = success != null && failure != null
    val isFailed get() = success == null
}
