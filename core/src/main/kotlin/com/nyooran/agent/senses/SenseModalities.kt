package com.nyooran.agent.senses

/**
 * Phase 2 modality contracts — typed request/result pairs for each
 * [SenseCapability].
 *
 * These contracts are device-neutral. They describe what data flows
 * in and out of each capability without assuming BLE, Lua, or Halo.
 * Raw media contracts are kept separate from semantic results
 * (transcripts, descriptions) — the endpoint layer captures/emits
 * raw media; model enrichment happens above (AD-6).
 */

// ------------------------------------------------------------------ shared media types

// AudioFormat and ImageFormat are defined in Senses.kt and reused here.

/**
 * Text content with optional formatting hint.
 */
data class TextContent(
    val text: String,
    /** Markdown, plain, or device-specific formatting hint. */
    val format: TextFormat = TextFormat.PLAIN,
)

enum class TextFormat { PLAIN, MARKDOWN }

/**
 * Visual presentation content. The [kind] determines how [payload]
 * is interpreted by the endpoint. For [VisualKind.DEVICE_NATIVE], the
 * [format] field identifies the content format (e.g. "hsd" for Halo
 * Scene Description, "hrp" for pre-compiled Halo Render Protocol).
 * The endpoint uses [format] to decide whether compilation is needed.
 */
data class VisualContent(
    val kind: VisualKind,
    val payload: ByteArray,
    /** Content format for DEVICE_NATIVE (e.g. "hsd", "hrp"). Null for IMAGE/TEXT. */
    val format: String? = null,
    /** True if this presentation should replace the current one. */
    val replaceCurrent: Boolean = true,
) {
    enum class VisualKind {
        /** Opaque device-specific presentation payload. */
        DEVICE_NATIVE,
        /** Raw image bytes (JPEG, PNG, etc.). */
        IMAGE,
        /** Simple text rendered by the endpoint. */
        TEXT,
    }
}

/**
 * An interaction event from the user.
 */
sealed interface InteractionEvent {
    val timestamp: Long

    /** A tap gesture (single, double, triple). */
    data class Tap(val gesture: TapGesture, override val timestamp: Long = System.currentTimeMillis()) : InteractionEvent

    /** A button press (single, double, long). */
    data class Button(val gesture: ButtonGesture, override val timestamp: Long = System.currentTimeMillis()) : InteractionEvent

    /** User approval of a pending request. */
    data class Approval(val approved: Boolean, override val timestamp: Long = System.currentTimeMillis()) : InteractionEvent

    /** User selection from a set of options. */
    data class Selection(val selectedIndex: Int, override val timestamp: Long = System.currentTimeMillis()) : InteractionEvent

    /** Text input from the user. */
    data class TextEntry(val text: String, override val timestamp: Long = System.currentTimeMillis()) : InteractionEvent

    /** Endpoint disconnected. */
    data object Disconnected : InteractionEvent { override val timestamp: Long = 0 }
}

enum class TapGesture { SINGLE, DOUBLE, TRIPLE }
enum class ButtonGesture { SINGLE, DOUBLE, LONG }

/**
 * Genuine endpoint/environment status. Only fields that are actually
 * available are populated; absent fields are null, not zero.
 */
data class EndpointStatus(
    val batteryLevel: Int? = null,
    val batteryVoltage: Int? = null,
    val batteryCharging: Boolean? = null,
    val temperature: Float? = null,
    /** Additional device-specific status as opaque key-value pairs. */
    val extras: Map<String, String> = emptyMap(),
)

// ------------------------------------------------------------------ request types

/**
 * Base interface for all capability requests.
 * Every request has a deadline (max duration) and is cancellable.
 */
interface SenseRequest {
    /** Maximum duration in milliseconds before the operation times out. */
    val timeoutMillis: Long
}

/** Request for [SenseCapability.AudioInput]. */
data class AudioInputRequest(
    val maxDurationMillis: Long,
    val gain: Int = 0,
    val aec: Boolean = true,
    val voice: Boolean = true,
    val maxBytes: Int,
    override val timeoutMillis: Long = maxDurationMillis + 2_000,
) : SenseRequest

/** Request for [SenseCapability.AudioOutput]. */
data class AudioOutputRequest(
    val audio: ByteArray,
    val format: AudioFormat,
    val volume: Int = 80,
    override val timeoutMillis: Long = 30_000,
) : SenseRequest

/** Request for [SenseCapability.ImageInput]. */
data class ImageInputRequest(
    val resolution: Int,
    val maxBytes: Int,
    /** Device-specific capture parameters (e.g. pan, qualityIndex, raw for Halo). */
    val deviceOptions: Map<String, Any> = emptyMap(),
    override val timeoutMillis: Long = 10_000,
) : SenseRequest

/** Request for [SenseCapability.VisualOutput]. */
data class VisualOutputRequest(
    val content: VisualContent,
    override val timeoutMillis: Long = 5_000,
) : SenseRequest

/** Request for [SenseCapability.TextOutput]. */
data class TextOutputRequest(
    val content: TextContent,
    override val timeoutMillis: Long = 5_000,
) : SenseRequest

/** Request for [SenseCapability.TextInput]. */
data class TextInputRequest(
    val prompt: String? = null,
    override val timeoutMillis: Long = 60_000,
) : SenseRequest

/** Request for [SenseCapability.InteractionInput]. */
data class InteractionInputRequest(
    val acceptedGestures: Set<InteractionType> = emptySet(),
    val prompt: String? = null,
    val options: List<String> = emptyList(),
    override val timeoutMillis: Long = 60_000,
) : SenseRequest

/** Types of interactions an endpoint can accept. */
enum class InteractionType {
    TAP_SINGLE, TAP_DOUBLE, TAP_TRIPLE,
    BUTTON_SINGLE, BUTTON_DOUBLE, BUTTON_LONG,
    APPROVAL, SELECTION, TEXT_ENTRY,
}

/** Request for [SenseCapability.StatusInput]. */
data class StatusInputRequest(
    override val timeoutMillis: Long = 5_000,
) : SenseRequest

// ------------------------------------------------------------------ result types

/**
 * Base interface for all capability results.
 * Every result carries provenance (C2-03).
 */
interface SenseResult {
    val provenance: Provenance
}

/**
 * Provenance metadata attached to every result.
 * See AD-5: "A result that cannot answer 'where did this come from?'
 * cannot be trusted in the workbench."
 */
data class Provenance(
    val operationId: String,
    val endpointId: EndpointId,
    val backendName: String,
    val backendKind: BackendKind,
    val capability: SenseCapability,
    val origin: ResultOrigin,
    /** Fixture ID when [origin] is [ResultOrigin.FIXTURE]. */
    val fixtureId: String? = null,
    /** Media format when applicable. */
    val mediaFormat: MediaFormat? = null,
    /** Transformations applied to the raw data. */
    val transformations: List<Transformation> = emptyList(),
    val startedAt: Long,
    val completedAt: Long,
    /** Delivery status for output operations. */
    val deliveryStatus: DeliveryStatus = DeliveryStatus.DELIVERED,
)

/** Where the result data originated. */
enum class ResultOrigin {
    CAMERA, MICROPHONE, KEYBOARD, ATTACHMENT,
    FIXTURE, MODEL, SPEAKER, DISPLAY, SENSOR,
}

/** Media format descriptor for provenance. */
data class MediaFormat(
    val encoding: String,
    val mime: String,
    val width: Int? = null,
    val height: Int? = null,
    val durationMillis: Long? = null,
    val sampleRate: Int? = null,
    val channels: Int? = null,
)

/** Transformations applied between raw capture and the returned result. */
enum class Transformation {
    NORMALIZATION,
    ASR_TRANSCRIPTION,
    VISION_ANALYSIS,
    TTS_SYNTHESIS,
    FORMAT_CONVERSION,
    DOWNSAMPLING,
    PRESENTATION_COMPILATION,
}

/** Delivery status for output operations. */
enum class DeliveryStatus {
    DELIVERED,
    PARTIALLY_DELIVERED,
    FAILED,
}

/** Result of [SenseCapability.AudioInput]. */
data class AudioInputResult(
    val audio: ByteArray,
    val format: AudioFormat,
    val durationMillis: Long,
    override val provenance: Provenance,
) : SenseResult

/** Result of [SenseCapability.AudioOutput]. */
data class AudioOutputResult(
    override val provenance: Provenance,
) : SenseResult

/** Result of [SenseCapability.ImageInput]. */
data class ImageInputResult(
    val image: ByteArray,
    val format: ImageFormat,
    val isRaw: Boolean,
    override val provenance: Provenance,
) : SenseResult

/** Result of [SenseCapability.VisualOutput]. */
data class VisualOutputResult(
    override val provenance: Provenance,
) : SenseResult

/** Result of [SenseCapability.TextOutput]. */
data class TextOutputResult(
    override val provenance: Provenance,
) : SenseResult

/** Result of [SenseCapability.TextInput]. */
data class TextInputResult(
    val text: String,
    override val provenance: Provenance,
) : SenseResult

/** Result of [SenseCapability.InteractionInput]. */
data class InteractionInputResult(
    val event: InteractionEvent,
    override val provenance: Provenance,
) : SenseResult

/** Result of [SenseCapability.StatusInput]. */
data class StatusInputResult(
    val status: EndpointStatus,
    override val provenance: Provenance,
) : SenseResult
