package com.nyooran.agent.senses

/**
 * Phase 2 core contracts — sense endpoint identity, profiles, and capabilities.
 *
 * These types replace the flat `SensesDevice` + `DeviceFeature` model with
 * a richer, device-neutral abstraction that can describe physical Halo,
 * phone, chat, and simulator endpoints without Halo-specific vocabulary.
 *
 * Design rules (from AGENT_SENSES_ARCHITECTURE.md AD-7):
 * - `core` contains no Android, BLE, Gemma, Node, Ktor, Compose, Python, or firmware types.
 * - Media has explicit format metadata and enforced maximum sizes.
 * - Operations are cancellable and have deadlines.
 * - Every result has provenance.
 * - Errors map to stable typed categories.
 * - Capability support is discoverable before invocation and rechecked by the backend.
 * - Physical and simulated implementations pass the same applicable contract suite.
 */

/**
 * Stable identifier for a sense endpoint within a session.
 *
 * The [SenseEndpointRegistry] accepts an endpoint with an endpoint-provided
 * ID. The application is responsible for assigning stable, non-reusable IDs
 * per physical or logical device. The registry treats the ID as an identity
 * key and does not silently reuse it after unbind; re-binding with the same
 * ID creates a fresh endpoint instance and cancels any previous one.
 */
@JvmInline
value class EndpointId(val value: String)

/**
 * What kind of backing implementation provides an endpoint.
 *
 * Used in provenance to distinguish real hardware from simulation.
 */
enum class BackendKind {
    /** Physical device (e.g. Halo glasses over BLE). */
    PHYSICAL,

    /** Phone-local resources (microphone, speaker, camera, display). */
    PHONE,

    /** Chat window / conversation interface. */
    CHAT,

    /** Deterministic in-process simulation for testing. */
    SIMULATOR,

    /** On-device model inference (Gemma, TTS). */
    MODEL,
}

/**
 * Connection/availability state of an endpoint.
 *
 * Distinct from capability support: an endpoint may be connected but
 * not ready (e.g. BLE connected but Lua runtime not yet started), or
 * ready but with a specific capability temporarily unavailable.
 */
enum class EndpointState {
    /** Not connected or not yet bound. */
    DISCONNECTED,

    /** Connection in progress. */
    CONNECTING,

    /** Connected and ready for operations. */
    READY,

    /** Connected but temporarily unable to process operations. */
    BUSY,

    /** Connection lost or failed. */
    FAILED,
}

/**
 * A logical capability that an endpoint can provide.
 *
 * Sealed to ensure the capability set is closed and exhaustively
 * matchable. Each capability has an associated request/result type
 * in the modality contracts (C2-02).
 */
sealed interface SenseCapability {
    /** Input: capture audio from the endpoint environment. */
    data object AudioInput : SenseCapability

    /** Output: play audio through the endpoint. */
    data object AudioOutput : SenseCapability

    /** Input: capture an image from the endpoint environment. */
    data object ImageInput : SenseCapability

    /** Output: present visual content on the endpoint. */
    data object VisualOutput : SenseCapability

    /** Output: present text content on the endpoint. */
    data object TextOutput : SenseCapability

    /** Input: receive typed text from the user. */
    data object TextInput : SenseCapability

    /** Input: await a discrete interaction (tap, button, approval, selection). */
    data object InteractionInput : SenseCapability

    /** Input: read genuine endpoint/environment state (battery, sensors). */
    data object StatusInput : SenseCapability
}

/**
 * Resource limits for a specific capability on a specific endpoint.
 *
 * All limits are enforced at the boundary; requests exceeding these
 * are rejected with [SensesError.LimitExceeded] before any I/O.
 */
data class SenseLimits(
    /** Maximum duration in milliseconds, or null for unbounded. */
    val maxDurationMillis: Long? = null,
    /** Maximum payload size in bytes, or null for unbounded. */
    val maxBytes: Long? = null,
    /** Maximum concurrent operations of this capability, or null for unlimited. */
    val maxConcurrent: Int? = null,
    /** Required permissions (opaque strings checked by the backend). */
    val requiredPermissions: Set<String> = emptySet(),
)

/**
 * Describes which operations can run concurrently on an endpoint.
 *
 * Operations are grouped into resource domains. Operations in the same
 * domain are serialized; operations in different domains may overlap.
 * Conflicts are symmetric: if A conflicts with B, B conflicts with A.
 */
data class ConcurrencyProfile(
    /**
     * Maps each capability to a resource domain name.
     * Capabilities sharing a domain are serialized.
     */
    val resourceDomains: Map<SenseCapability, String> = emptyMap(),
    /**
     * Explicit conflict pairs that must be serialized even across domains.
     * Each pair is unordered (A conflicts with B implies B conflicts with A).
     */
    val explicitConflicts: Set<Pair<SenseCapability, SenseCapability>> = emptySet(),
) {
    /**
     * Returns true if [a] and [b] can run concurrently on this endpoint.
     */
    fun canOverlap(a: SenseCapability, b: SenseCapability): Boolean {
        if (a == b) return false
        val domainA = resourceDomains[a]
        val domainB = resourceDomains[b]
        if (domainA != null && domainA == domainB) return false
        val pair = a to b
        val reverse = b to a
        if (pair in explicitConflicts || reverse in explicitConflicts) return false
        return true
    }
}

/**
 * The complete capability profile of a sense endpoint.
 *
 * An endpoint truthfully describes what it supports. Unsupported
 * capabilities are simply absent from [capabilities]; they are not
 * represented as null or disabled entries.
 */
data class SenseProfile(
    val endpointId: EndpointId,
    val backendName: String,
    val backendKind: BackendKind,
    val state: EndpointState,
    val capabilities: Set<SenseCapability>,
    val limits: Map<SenseCapability, SenseLimits>,
    val concurrency: ConcurrencyProfile,
    /**
     * Optional human-readable label for UI display.
     * Not used for routing or identity.
     */
    val displayName: String? = null,
) {
    /**
     * Returns true if this endpoint supports [capability].
     */
    fun supports(capability: SenseCapability): Boolean = capability in capabilities

    /**
     * Returns the limits for [capability], or null if unsupported.
     */
    fun limitsFor(capability: SenseCapability): SenseLimits? = limits[capability]

    /**
     * Returns true if [a] and [b] can run concurrently on this endpoint.
     */
    fun canOverlap(a: SenseCapability, b: SenseCapability): Boolean = concurrency.canOverlap(a, b)
}
