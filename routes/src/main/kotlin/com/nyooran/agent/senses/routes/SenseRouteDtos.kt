package com.nyooran.agent.senses.routes

import kotlinx.serialization.Serializable

/**
 * Phase 4 A4-01: Generic sense capability route DTOs.
 *
 * These are the request/response types for the device-neutral sense API.
 * Every request includes optional `endpoint_id` for targeting a specific
 * endpoint. Every response includes provenance with endpoint identity.
 */

// ------------------------------------------------------------------ capabilities

@Serializable
data class CapabilitiesResponse(
    val endpoints: List<EndpointProfileDto>,
)

@Serializable
data class EndpointProfileDto(
    val endpoint_id: String,
    val backend_name: String,
    val backend_kind: String,
    val state: String,
    val capabilities: List<String>,
    val display_name: String? = null,
)

// ------------------------------------------------------------------ listen

@Serializable
data class ListenRequest(
    val endpoint_id: String? = null,
    val max_duration_millis: Long = 5000,
    val gain: Int = 0,
    val aec: Boolean = true,
    val voice: Boolean = true,
    val max_bytes: Int = 65536,
    val timeout_millis: Long = 0,
)

@Serializable
data class ListenResponse(
    val audio_base64: String,
    val format: AudioFormatDto,
    val duration_millis: Long,
    val provenance: ProvenanceDto,
)

// ------------------------------------------------------------------ look

@Serializable
data class LookRequest(
    val endpoint_id: String? = null,
    val resolution: Int = 640,
    val quality_index: Int = 0,
    val max_bytes: Int = 65536,
    val raw: Boolean = false,
    val timeout_millis: Long = 30000,
)

@Serializable
data class LookResponse(
    val image_base64: String,
    val format: ImageFormatDto,
    val is_raw: Boolean,
    val provenance: ProvenanceDto,
)

// ------------------------------------------------------------------ wait

@Serializable
data class WaitRequest(
    val endpoint_id: String? = null,
    val accepted_gestures: List<String> = emptyList(),
    val timeout_millis: Long = 30000,
)

@Serializable
data class WaitResponse(
    val event_type: String,
    val gesture: String? = null,
    val approved: Boolean? = null,
    val selected_index: Int? = null,
    val text: String? = null,
    val provenance: ProvenanceDto,
)

// ------------------------------------------------------------------ speak

@Serializable
data class SpeakRequest(
    val endpoint_id: String? = null,
    val audio_base64: String? = null,
    val format: AudioFormatDto? = null,
    val timeout_millis: Long = 30000,
)

@Serializable
data class SpeakResponse(
    val provenance: ProvenanceDto,
)

// ------------------------------------------------------------------ say (semantic TTS → playback)

@Serializable
data class SayRequest(
    val endpoint_id: String? = null,
    val text: String,
)

@Serializable
data class SayResponse(
    val tts_provenance: ProvenanceDto,
    val output_provenance: ProvenanceDto,
)

// ------------------------------------------------------------------ present

@Serializable
data class PresentRequest(
    val endpoint_id: String? = null,
    val kind: String,  // "device_native", "image", "text"
    val format: String? = null,  // for device_native: "hsd", "hrp", etc.
    val payload_base64: String? = null,
    val text: String? = null,
    val timeout_millis: Long = 30000,
)

@Serializable
data class PresentResponse(
    val provenance: ProvenanceDto,
)

// ------------------------------------------------------------------ status

@Serializable
data class StatusRequest(
    val endpoint_id: String? = null,
    val timeout_millis: Long = 10000,
)

@Serializable
data class StatusResponse(
    val battery_level: Int? = null,
    val battery_voltage: Int? = null,
    val battery_charging: Boolean? = null,
    val provenance: ProvenanceDto,
)

// ------------------------------------------------------------------ shared

@Serializable
data class AudioFormatDto(
    val sample_rate: Int,
    val bits_per_sample: Int,
    val channels: Int,
    val encoding: String,
    val mime: String,
)

@Serializable
data class ImageFormatDto(
    val encoding: String,
    val mime: String,
    val width: Int,
    val height: Int,
)

@Serializable
data class ProvenanceDto(
    val operation_id: String,
    val endpoint_id: String,
    val backend_name: String,
    val backend_kind: String,
    val capability: String,
    val origin: String,
    val fixture_id: String? = null,
    val started_at: Long,
    val completed_at: Long,
)

@Serializable
data class ErrorResponse(
    val error: String,
    val category: String? = null,
    val endpoint_id: String? = null,
)
