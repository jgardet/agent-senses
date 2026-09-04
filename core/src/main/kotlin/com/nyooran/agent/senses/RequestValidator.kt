package com.nyooran.agent.senses

/**
 * Phase 2 C2-04: Request validation and partial multimodal delivery.
 *
 * [RequestValidator] provides typed validation for each capability request.
 * Invalid requests are rejected with [FailureCategory.REJECTED] before any
 * I/O, rather than coercing values or silently proceeding.
 *
 * [MultiModalResult] supports combined operations that deliver multiple
 * modalities (e.g. speak + present) where some may succeed and others fail.
 */

/**
 * Validates capability requests against endpoint limits before invocation.
 *
 * Endpoints call [validate] with the request and their profile; it returns
 * null if the request is valid, or a [SenseFailure] with [FailureCategory.REJECTED]
 * or [FailureCategory.LIMIT_EXCEEDED] if the request is invalid.
 */
object RequestValidator {

    /**
     * Validate [request] against [profile] for [capability].
     * Returns null if valid, or a [SenseFailure] describing the violation.
     */
    fun validate(
        capability: SenseCapability,
        request: SenseRequest,
        profile: SenseProfile,
    ): SenseFailure? {
        if (!profile.supports(capability)) {
            return SenseFailure(
                FailureCategory.UNAVAILABLE,
                "Endpoint ${profile.endpointId.value} does not support $capability",
                endpointId = profile.endpointId,
                capability = capability,
            )
        }

        val limits = profile.limitsFor(capability)

        // Check timeout
        if (request.timeoutMillis <= 0) {
            return SenseFailure(
                FailureCategory.REJECTED,
                "timeoutMillis must be positive, got ${request.timeoutMillis}",
                endpointId = profile.endpointId,
                capability = capability,
            )
        }

        // Check duration limits
        if (limits?.maxDurationMillis != null && request is AudioInputRequest) {
            if (request.maxDurationMillis > limits.maxDurationMillis) {
                return SenseFailure(
                    FailureCategory.LIMIT_EXCEEDED,
                    "maxDurationMillis ${request.maxDurationMillis} exceeds limit ${limits.maxDurationMillis}",
                    endpointId = profile.endpointId,
                    capability = capability,
                )
            }
            if (request.maxDurationMillis <= 0) {
                return SenseFailure(
                    FailureCategory.REJECTED,
                    "maxDurationMillis must be positive, got ${request.maxDurationMillis}",
                    endpointId = profile.endpointId,
                    capability = capability,
                )
            }
        }

        // Check byte limits
        if (limits?.maxBytes != null) {
            val requestBytes = when (request) {
                is AudioInputRequest -> request.maxBytes.toLong()
                is ImageInputRequest -> request.maxBytes.toLong()
                is AudioOutputRequest -> request.audio.size.toLong()
                is VisualOutputRequest -> request.content.payload.size.toLong()
                is TextOutputRequest -> request.content.text.encodeToByteArray().size.toLong()
                else -> 0L
            }
            if (requestBytes > limits.maxBytes) {
                return SenseFailure(
                    FailureCategory.LIMIT_EXCEEDED,
                    "request size $requestBytes exceeds limit ${limits.maxBytes}",
                    endpointId = profile.endpointId,
                    capability = capability,
                )
            }
        }

        // Check permissions
        if (limits?.requiredPermissions?.isNotEmpty() == true) {
            // Permissions are checked by the backend at invocation time;
            // here we just verify the request declares what's needed.
            // The actual permission check is backend-specific.
        }

        // Capability-specific validation
        when (request) {
            is AudioInputRequest -> {
                if (request.maxBytes <= 0) {
                    return SenseFailure(FailureCategory.REJECTED, "maxBytes must be positive",
                        endpointId = profile.endpointId, capability = capability)
                }
            }
            is ImageInputRequest -> {
                if (request.maxBytes <= 0) {
                    return SenseFailure(FailureCategory.REJECTED, "maxBytes must be positive",
                        endpointId = profile.endpointId, capability = capability)
                }
                if (request.resolution <= 0) {
                    return SenseFailure(FailureCategory.REJECTED, "resolution must be positive",
                        endpointId = profile.endpointId, capability = capability)
                }
            }
            is AudioOutputRequest -> {
                if (request.audio.isEmpty()) {
                    return SenseFailure(FailureCategory.REJECTED, "audio payload must not be empty",
                        endpointId = profile.endpointId, capability = capability)
                }
                if (request.format.sampleRate <= 0) {
                    return SenseFailure(FailureCategory.REJECTED, "sampleRate must be positive",
                        endpointId = profile.endpointId, capability = capability)
                }
            }
            is VisualOutputRequest -> {
                if (request.content.payload.isEmpty() && request.content.kind != VisualContent.VisualKind.TEXT) {
                    return SenseFailure(FailureCategory.REJECTED, "visual payload must not be empty",
                        endpointId = profile.endpointId, capability = capability)
                }
            }
            is TextOutputRequest -> {
                if (request.content.text.isEmpty()) {
                    return SenseFailure(FailureCategory.REJECTED, "text must not be empty",
                        endpointId = profile.endpointId, capability = capability)
                }
            }
            else -> { /* no additional validation */ }
        }

        return null
    }
}

/**
 * Result of a combined multimodal operation.
 *
 * When an agent requests multiple outputs simultaneously (e.g. speak + present),
 * each modality may succeed or fail independently. This preserves partial
 * success visibility — the agent can see that audio was delivered even if
 * the visual presentation failed.
 */
data class MultiModalResult(
    val results: List<ModalityResult>,
) {
    /** True if all modalities succeeded. */
    val allSucceeded: Boolean get() = results.all { it is ModalityResult.Success }

    /** True if at least one modality succeeded. */
    val anySucceeded: Boolean get() = results.any { it is ModalityResult.Success }

    /** True if at least one modality failed. */
    val anyFailed: Boolean get() = results.any { it is ModalityResult.Failure }

    /** The delivery status of the overall operation. */
    val deliveryStatus: DeliveryStatus get() = when {
        allSucceeded -> DeliveryStatus.DELIVERED
        anySucceeded -> DeliveryStatus.PARTIALLY_DELIVERED
        else -> DeliveryStatus.FAILED
    }
}

/**
 * A single modality's result within a [MultiModalResult].
 */
sealed interface ModalityResult {
    val capability: SenseCapability

    data class Success(
        override val capability: SenseCapability,
        val result: SenseResult,
    ) : ModalityResult

    data class Failure(
        override val capability: SenseCapability,
        val error: SenseFailure,
    ) : ModalityResult
}
