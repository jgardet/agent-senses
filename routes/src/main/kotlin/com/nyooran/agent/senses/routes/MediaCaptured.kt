package com.nyooran.agent.senses.routes

/**
 * A bounded media artifact produced or consumed by a sense route.
 *
 * Suitable for diagnostics preview; the base64 payload is the same bounded
 * output the agent receives, so it respects `max_bytes` and `max_duration`
 * limits. No unbounded raw dumps.
 */
data class MediaCaptured(
    val kind: String,
    val base64: String,
    val mimeType: String,
    val endpointId: String,
    val operationId: String,
    val provenance: Map<String, String> = emptyMap(),
)
