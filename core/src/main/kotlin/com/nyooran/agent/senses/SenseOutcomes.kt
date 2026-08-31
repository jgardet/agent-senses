package com.nyooran.agent.senses

/**
 * Phase 2 typed outcomes and errors.
 *
 * Every capability operation returns a [SenseOutcome] — either a
 * success with a typed result, or a typed failure with a stable
 * category. Callers switch on the category; they never inspect
 * backend exception classes or message text (AD-4, C2-04).
 *
 * Coroutine cancellation is preserved: a cancelled operation throws
 * [CancellationException] through the normal Kotlin coroutine mechanism,
 * it is not wrapped as a [SenseFailure].
 */

/**
 * The outcome of a capability operation.
 *
 * Sealed to ensure exhaustive handling: every operation either succeeds
 * with a typed result or fails with a typed failure.
 */
sealed interface SenseOutcome<out T : SenseResult> {
    data class Success<T : SenseResult>(val result: T) : SenseOutcome<T>
    data class Failure(val error: SenseFailure) : SenseOutcome<Nothing>
}

/**
 * A typed failure with a stable category.
 *
 * Categories are exhaustive and stable across backends. The [message]
 * is human-readable and may vary; [category] is the programmatic
 * discriminator.
 */
data class SenseFailure(
    val category: FailureCategory,
    val message: String,
    val cause: Throwable? = null,
    /** The endpoint that produced this failure. */
    val endpointId: EndpointId? = null,
    /** The capability that was requested. */
    val capability: SenseCapability? = null,
)

/**
 * Stable failure categories.
 *
 * These map 1:1 to the old [SensesError.Category] enum but are
 * defined here as part of the Phase 2 contract.
 */
enum class FailureCategory {
    /** The endpoint does not support the requested capability. */
    UNAVAILABLE,

    /** The endpoint is not connected or connection was lost. */
    DISCONNECTED,

    /** The operation exceeded its deadline. */
    TIMEOUT,

    /** The operation was cancelled by the caller. */
    CANCELLED,

    /** The request was rejected (invalid parameters, unsupported option). */
    REJECTED,

    /** The request exceeded a declared limit (bytes, duration, etc.). */
    LIMIT_EXCEEDED,

    /** A protocol-level error occurred (malformed data, framing error). */
    PROTOCOL,

    /** The caller lacks permission for the requested operation. */
    PERMISSION_DENIED,

    /** A required model (Gemma, TTS) is not available. */
    MODEL_UNAVAILABLE,

    /** An internal error occurred. */
    INTERNAL,
}

/**
 * Convenience functions for constructing typed outcomes.
 */
object SenseOutcomes {
    fun <T : SenseResult> success(result: T): SenseOutcome.Success<T> = SenseOutcome.Success(result)

    fun unavailable(message: String, endpointId: EndpointId? = null, capability: SenseCapability? = null) =
        SenseOutcome.Failure(SenseFailure(FailureCategory.UNAVAILABLE, message, endpointId = endpointId, capability = capability))

    fun disconnected(message: String, endpointId: EndpointId? = null, capability: SenseCapability? = null) =
        SenseOutcome.Failure(SenseFailure(FailureCategory.DISCONNECTED, message, endpointId = endpointId, capability = capability))

    fun timeout(message: String, endpointId: EndpointId? = null, capability: SenseCapability? = null) =
        SenseOutcome.Failure(SenseFailure(FailureCategory.TIMEOUT, message, endpointId = endpointId, capability = capability))

    fun cancelled(message: String, endpointId: EndpointId? = null, capability: SenseCapability? = null) =
        SenseOutcome.Failure(SenseFailure(FailureCategory.CANCELLED, message, endpointId = endpointId, capability = capability))

    fun rejected(message: String, endpointId: EndpointId? = null, capability: SenseCapability? = null) =
        SenseOutcome.Failure(SenseFailure(FailureCategory.REJECTED, message, endpointId = endpointId, capability = capability))

    fun limitExceeded(message: String, endpointId: EndpointId? = null, capability: SenseCapability? = null) =
        SenseOutcome.Failure(SenseFailure(FailureCategory.LIMIT_EXCEEDED, message, endpointId = endpointId, capability = capability))

    fun protocol(message: String, endpointId: EndpointId? = null, capability: SenseCapability? = null) =
        SenseOutcome.Failure(SenseFailure(FailureCategory.PROTOCOL, message, endpointId = endpointId, capability = capability))

    fun permissionDenied(message: String, endpointId: EndpointId? = null, capability: SenseCapability? = null) =
        SenseOutcome.Failure(SenseFailure(FailureCategory.PERMISSION_DENIED, message, endpointId = endpointId, capability = capability))

    fun modelUnavailable(message: String, endpointId: EndpointId? = null, capability: SenseCapability? = null) =
        SenseOutcome.Failure(SenseFailure(FailureCategory.MODEL_UNAVAILABLE, message, endpointId = endpointId, capability = capability))

    fun internal(message: String, cause: Throwable? = null, endpointId: EndpointId? = null, capability: SenseCapability? = null) =
        SenseOutcome.Failure(SenseFailure(FailureCategory.INTERNAL, message, cause, endpointId, capability))
}

/**
 * Bridge from the old [SensesError] to the new [SenseFailure].
 * Used during migration; removed when all callers use Phase 2 types.
 */
fun SensesError.toSenseFailure(endpointId: EndpointId? = null, capability: SenseCapability? = null): SenseFailure {
    val category = when (category) {
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
    }
    return SenseFailure(category, message ?: "unknown error", cause, endpointId, capability)
}
