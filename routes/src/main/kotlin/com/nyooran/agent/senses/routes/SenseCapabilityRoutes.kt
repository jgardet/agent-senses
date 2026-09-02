package com.nyooran.agent.senses.routes

import com.nyooran.agent.senses.*
import com.nyooran.agent.senses.orchestration.SemanticSenseWorkflows
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * Phase 4 A4-01: Generic sense capability routes.
 *
 * Device-neutral HTTP routes that delegate to the [SenseEndpointRegistry].
 * Routes validate and delegate; they contain no backend, model, fixture,
 * or fallback logic.
 *
 * Every route:
 * - Accepts optional `endpoint_id` for targeting
 * - Includes provenance in the response
 * - Uses [RequestValidator] for strict validation
 * - Maps [SensesError] to typed HTTP status codes
 * - Re-throws coroutine cancellation before broad error handling
 * - Returns non-success HTTP status for failed/unavailable operations
 */
fun Route.senseCapabilityRoutes(
    registry: SenseEndpointRegistry,
    authToken: String,
    workflows: SemanticSenseWorkflows? = null,
    onMediaCaptured: ((MediaCaptured) -> Unit)? = null,
) {
    val json = Json { ignoreUnknownKeys = true }

    // ------------------------------------------------------------------ capabilities

    get("/v1/sense/capabilities") {
        if (!checkAuth(call, authToken)) return@get
        val profiles = registry.endpointProfiles.value.map { p ->
            EndpointProfileDto(
                endpoint_id = p.endpointId.value,
                backend_name = p.backendName,
                backend_kind = p.backendKind.name,
                state = p.state.name,
                capabilities = p.capabilities.map { it.name() },
                display_name = p.displayName,
            )
        }
        call.respond(CapabilitiesResponse(profiles))
    }

    // ------------------------------------------------------------------ listen

    post("/v1/sense/listen") {
        if (!checkAuth(call, authToken)) return@post
        if (workflows == null) {
            respondError(call, SensesError.Unavailable("semantic workflows not configured"))
            return@post
        }
        val req = safeReceive<ListenRequest>(call, json) ?: return@post
        val endpoint = resolveEndpoint(call, registry, SenseCapability.AudioInput, req.endpoint_id) ?: return@post
        val senseReq = AudioInputRequest(
            maxDurationMillis = req.max_duration_millis,
            gain = req.gain,
            aec = req.aec,
            voice = req.voice,
            maxBytes = req.max_bytes,
            timeoutMillis = if (req.timeout_millis > 0) req.timeout_millis else req.max_duration_millis + 2000,
        )
        val validationError = RequestValidator.validate(SenseCapability.AudioInput, senseReq, endpoint.profile)
        if (validationError != null) {
            respondError(call, validationError)
            return@post
        }
        try {
            val result = workflows.listenAndTranscribe(
                endpointId = endpoint.profile.endpointId,
                request = senseReq,
                keepRaw = req.raw,
            )
            result.fold(
                onSuccess = { r ->
                    if (req.raw && r.rawAudio != null) {
                        onMediaCaptured?.invoke(
                            MediaCaptured(
                                kind = "audio",
                                base64 = Base64.getEncoder().encodeToString(r.rawAudio),
                                mimeType = r.rawAudioFormat?.mime ?: "audio/wav",
                                endpointId = r.rawAudioProvenance.endpointId.value,
                                operationId = r.rawAudioProvenance.operationId,
                                provenance = mapOf(
                                    "capability" to "AudioInput",
                                    "transcript" to r.transcript,
                                    "duration_millis" to (r.rawAudioProvenance.mediaFormat?.durationMillis ?: 0L).toString(),
                                ),
                            )
                        )
                    }
                    call.respond(r.toListenResponse(req.raw))
                },
                onFailure = { e ->
                    when (e) {
                        is SensesError -> respondError(call, e)
                        is CancellationException -> throw e
                        else -> respondError(call, SensesError.Internal(e.message ?: "listen failed"))
                    }
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            respondError(call, SensesError.Internal(e.message ?: "listen failed"))
        }
    }

    // ------------------------------------------------------------------ look

    post("/v1/sense/look") {
        if (!checkAuth(call, authToken)) return@post
        if (workflows == null) {
            respondError(call, SensesError.Unavailable("semantic workflows not configured"))
            return@post
        }
        val req = safeReceive<LookRequest>(call, json) ?: return@post
        val endpoint = resolveEndpoint(call, registry, SenseCapability.ImageInput, req.endpoint_id) ?: return@post
        val senseReq = ImageInputRequest(
            resolution = req.resolution,
            maxBytes = req.max_bytes,
            deviceOptions = buildMap {
                if (req.quality_index != 0) put("qualityIndex", req.quality_index)
                if (req.raw) put("raw", true)
            },
            timeoutMillis = req.timeout_millis,
        )
        val validationError = RequestValidator.validate(SenseCapability.ImageInput, senseReq, endpoint.profile)
        if (validationError != null) {
            respondError(call, validationError)
            return@post
        }
        try {
            val result = workflows.lookAndObserve(
                endpointId = endpoint.profile.endpointId,
                request = senseReq,
                prompt = req.prompt,
                keepRaw = req.raw,
            )
            result.fold(
                onSuccess = { r ->
                    if (req.raw && r.rawImage != null) {
                        onMediaCaptured?.invoke(
                            MediaCaptured(
                                kind = "image",
                                base64 = Base64.getEncoder().encodeToString(r.rawImage),
                                mimeType = r.rawImageFormat?.mime ?: "image/jpeg",
                                endpointId = r.rawImageProvenance.endpointId.value,
                                operationId = r.rawImageProvenance.operationId,
                                provenance = mapOf(
                                    "capability" to "ImageInput",
                                    "description" to r.description,
                                ),
                            )
                        )
                    }
                    call.respond(r.toLookResponse(req.raw))
                },
                onFailure = { e ->
                    when (e) {
                        is SensesError -> respondError(call, e)
                        is CancellationException -> throw e
                        else -> respondError(call, SensesError.Internal(e.message ?: "look failed"))
                    }
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            respondError(call, SensesError.Internal(e.message ?: "look failed"))
        }
    }

    // ------------------------------------------------------------------ wait

    post("/v1/sense/wait") {
        if (!checkAuth(call, authToken)) return@post
        val req = safeReceive<WaitRequest>(call, json) ?: return@post
        val endpoint = resolveEndpoint(call, registry, SenseCapability.InteractionInput, req.endpoint_id) ?: return@post
        val acceptedGestures = req.accepted_gestures.mapNotNull { parseInteractionType(it) }.toSet()
        val senseReq = InteractionInputRequest(
            acceptedGestures = acceptedGestures,
            timeoutMillis = req.timeout_millis,
        )
        try {
            val result = endpoint.interactionInput(senseReq)
            val resp = when (val ev = result.event) {
                is InteractionEvent.Tap -> WaitResponse(
                    event_type = "tap",
                    gesture = when (ev.gesture) {
                        TapGesture.SINGLE -> "single"
                        TapGesture.DOUBLE -> "double"
                        TapGesture.TRIPLE -> "triple"
                    },
                    provenance = result.provenance.toDto(),
                )
                is InteractionEvent.Button -> WaitResponse(
                    event_type = "button",
                    gesture = when (ev.gesture) {
                        ButtonGesture.SINGLE -> "single"
                        ButtonGesture.DOUBLE -> "double"
                        ButtonGesture.LONG -> "long"
                    },
                    provenance = result.provenance.toDto(),
                )
                is InteractionEvent.Approval -> WaitResponse(
                    event_type = "approval",
                    approved = ev.approved,
                    provenance = result.provenance.toDto(),
                )
                is InteractionEvent.Selection -> WaitResponse(
                    event_type = "selection",
                    selected_index = ev.selectedIndex,
                    provenance = result.provenance.toDto(),
                )
                is InteractionEvent.TextEntry -> WaitResponse(
                    event_type = "text_entry",
                    text = ev.text,
                    provenance = result.provenance.toDto(),
                )
                InteractionEvent.Disconnected -> WaitResponse(
                    event_type = "disconnected",
                    provenance = result.provenance.toDto(),
                )
            }
            call.respond(resp)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SensesError) {
            respondError(call, e)
        } catch (e: Exception) {
            respondError(call, SensesError.Internal(e.message ?: "wait failed"))
        }
    }

    // ------------------------------------------------------------------ speak

    post("/v1/sense/speak") {
        if (!checkAuth(call, authToken)) return@post
        val req = safeReceive<SpeakRequest>(call, json) ?: return@post
        val endpoint = resolveEndpoint(call, registry, SenseCapability.AudioOutput, req.endpoint_id) ?: return@post
        if (req.audio_base64 == null || req.format == null) {
            respondError(call, SensesError.Rejected("audio_base64 and format are required"))
            return@post
        }
        val audio = try {
            Base64.getDecoder().decode(req.audio_base64)
        } catch (e: IllegalArgumentException) {
            respondError(call, SensesError.Rejected("invalid base64 audio"))
            return@post
        }
        val senseReq = AudioOutputRequest(
            audio = audio,
            format = AudioFormat(
                sampleRate = req.format.sample_rate,
                bitDepth = req.format.bits_per_sample,
                channels = req.format.channels,
                encoding = req.format.encoding,
                mime = req.format.mime,
            ),
            timeoutMillis = req.timeout_millis,
        )
        val validationError = RequestValidator.validate(SenseCapability.AudioOutput, senseReq, endpoint.profile)
        if (validationError != null) {
            respondError(call, validationError)
            return@post
        }
        try {
            val result = endpoint.audioOutput(senseReq)
            onMediaCaptured?.invoke(
                MediaCaptured(
                    kind = "audio",
                    base64 = req.audio_base64,
                    mimeType = req.format.mime,
                    endpointId = endpoint.profile.endpointId.value,
                    operationId = result.provenance.operationId,
                    provenance = mapOf(
                        "capability" to "AudioOutput",
                        "sample_rate" to req.format.sample_rate.toString(),
                        "encoding" to req.format.encoding,
                    ),
                )
            )
            call.respond(SpeakResponse(provenance = result.provenance.toDto()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: SensesError) {
            respondError(call, e)
        } catch (e: Exception) {
            respondError(call, SensesError.Internal(e.message ?: "speak failed"))
        }
    }

    // ------------------------------------------------------------------ say (semantic TTS → playback)

    post("/v1/sense/say") {
        if (!checkAuth(call, authToken)) return@post
        if (workflows == null) {
            respondError(call, SensesError.Unavailable("semantic workflows not configured"))
            return@post
        }
        val req = safeReceive<SayRequest>(call, json) ?: return@post
        if (req.text.isBlank()) {
            respondError(call, SensesError.Rejected("text must not be blank"))
            return@post
        }
        val endpointId = req.endpoint_id?.let { EndpointId(it) }
        try {
            val result = workflows.speakText(req.text, endpointId)
            result.fold(
                onSuccess = { r ->
                    r.audio?.let { audioBytes ->
                        onMediaCaptured?.invoke(
                            MediaCaptured(
                                kind = "audio",
                                base64 = Base64.getEncoder().encodeToString(audioBytes),
                                mimeType = r.format?.mime ?: "audio/wav",
                                endpointId = r.outputProvenance.endpointId.value,
                                operationId = r.outputProvenance.operationId,
                                provenance = mapOf(
                                    "capability" to "AudioOutput",
                                    "source" to "tts",
                                    "sample_rate" to (r.format?.sampleRate ?: 16000).toString(),
                                    "encoding" to (r.format?.encoding ?: "wav"),
                                    "text" to req.text,
                                ),
                            )
                        )
                    }
                    call.respond(SayResponse(
                        tts_provenance = r.ttsProvenance.toDto(),
                        output_provenance = r.outputProvenance.toDto(),
                    ))
                },
                onFailure = { e ->
                    when (e) {
                        is SensesError -> respondError(call, e)
                        else -> respondError(call, SensesError.Internal(e.message ?: "say failed"))
                    }
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            respondError(call, SensesError.Internal(e.message ?: "say failed"))
        }
    }

    // ------------------------------------------------------------------ present

    post("/v1/sense/present") {
        if (!checkAuth(call, authToken)) return@post
        val req = safeReceive<PresentRequest>(call, json) ?: return@post
        val endpoint = resolveEndpoint(call, registry, SenseCapability.VisualOutput, req.endpoint_id) ?: return@post
        val kind = when (req.kind) {
            "device_native" -> VisualContent.VisualKind.DEVICE_NATIVE
            "image" -> VisualContent.VisualKind.IMAGE
            "text" -> VisualContent.VisualKind.TEXT
            else -> {
                respondError(call, SensesError.Rejected("kind must be device_native, image, or text"))
                return@post
            }
        }
        val payload = if (req.payload_base64 != null) {
            try { Base64.getDecoder().decode(req.payload_base64) }
            catch (e: IllegalArgumentException) {
                respondError(call, SensesError.Rejected("invalid base64 payload"))
                return@post
            }
        } else if (req.text != null) {
            req.text.toByteArray(Charsets.UTF_8)
        } else {
            ByteArray(0)
        }
        val senseReq = VisualOutputRequest(
            content = VisualContent(kind, payload, format = req.format),
            timeoutMillis = req.timeout_millis,
        )
        val validationError = RequestValidator.validate(SenseCapability.VisualOutput, senseReq, endpoint.profile)
        if (validationError != null) {
            respondError(call, validationError)
            return@post
        }
        try {
            val result = endpoint.visualOutput(senseReq)
            if (kind == VisualContent.VisualKind.IMAGE && req.payload_base64 != null) {
                onMediaCaptured?.invoke(
                    MediaCaptured(
                        kind = "image",
                        base64 = req.payload_base64,
                        mimeType = "image/jpeg",
                        endpointId = endpoint.profile.endpointId.value,
                        operationId = result.provenance.operationId,
                        provenance = mapOf(
                            "capability" to "VisualOutput",
                            "kind" to "image",
                        ),
                    )
                )
            }
            call.respond(PresentResponse(provenance = result.provenance.toDto()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: SensesError) {
            respondError(call, e)
        } catch (e: Exception) {
            respondError(call, SensesError.Internal(e.message ?: "present failed"))
        }
    }

    // ------------------------------------------------------------------ status

    post("/v1/sense/status") {
        if (!checkAuth(call, authToken)) return@post
        val req = safeReceive<StatusRequest>(call, json) ?: return@post
        val endpoint = resolveEndpoint(call, registry, SenseCapability.StatusInput, req.endpoint_id) ?: return@post
        val senseReq = StatusInputRequest(timeoutMillis = req.timeout_millis)
        try {
            val result = endpoint.statusInput(senseReq)
            call.respond(StatusResponse(
                battery_level = result.status.batteryLevel,
                battery_voltage = result.status.batteryVoltage,
                battery_charging = result.status.batteryCharging,
                provenance = result.provenance.toDto(),
            ))
        } catch (e: CancellationException) {
            throw e
        } catch (e: SensesError) {
            respondError(call, e)
        } catch (e: Exception) {
            respondError(call, SensesError.Internal(e.message ?: "status failed"))
        }
    }
}

// ------------------------------------------------------------------ helpers

private suspend fun checkAuth(call: ApplicationCall, authToken: String): Boolean {
    val provided = call.request.header("Authorization")
    if (provided != "Bearer $authToken") {
        call.respond(HttpStatusCode.Unauthorized, ErrorResponse(error = "unauthorized"))
        return false
    }
    return true
}

private suspend inline fun <reified T> safeReceive(call: ApplicationCall, json: Json): T? {
    return try {
        json.decodeFromString(call.receiveText())
    } catch (e: Exception) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "invalid request: ${e.message}"))
        null
    }
}

private suspend fun resolveEndpoint(
    call: ApplicationCall,
    registry: SenseEndpointRegistry,
    capability: SenseCapability,
    endpointId: String?,
): SenseEndpoint? {
    if (endpointId != null) {
        val ep = registry.get(EndpointId(endpointId))
        if (ep == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse(
                error = "endpoint not found: $endpointId",
                endpoint_id = endpointId,
            ))
            return null
        }
        if (!ep.profile.supports(capability)) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse(
                error = "endpoint $endpointId does not support ${capability.name()}",
                category = "UNAVAILABLE",
                endpoint_id = endpointId,
            ))
            return null
        }
        return ep
    }
    val result = registry.resolve(capability)
    return when (result) {
        is ResolveResult.Resolved -> result.endpoint
        is ResolveResult.Ambiguous -> {
            call.respond(HttpStatusCode.Conflict, ErrorResponse(
                error = "multiple endpoints support ${capability.name()}: ${result.eligible.map { it.profile.endpointId.value }}",
                category = "AMBIGUOUS",
            ))
            null
        }
        is ResolveResult.NoEndpoint -> {
            call.respond(HttpStatusCode.NotFound, ErrorResponse(
                error = "no endpoint supports ${capability.name()}",
                category = "UNAVAILABLE",
            ))
            null
        }
        is ResolveResult.NotFound -> {
            call.respond(HttpStatusCode.NotFound, ErrorResponse(
                error = "endpoint not found: ${result.endpointId.value}",
                endpoint_id = result.endpointId.value,
            ))
            null
        }
        is ResolveResult.Unsupported -> {
            call.respond(HttpStatusCode.Conflict, ErrorResponse(
                error = "endpoint ${result.endpoint.profile.endpointId.value} does not support ${capability.name()}",
                category = "UNAVAILABLE",
                endpoint_id = result.endpoint.profile.endpointId.value,
            ))
            null
        }
    }
}

private suspend fun respondError(call: ApplicationCall, error: SenseFailure) {
    val status = when (error.category) {
        FailureCategory.UNAVAILABLE -> HttpStatusCode.NotFound
        FailureCategory.DISCONNECTED -> HttpStatusCode.Gone
        FailureCategory.TIMEOUT -> HttpStatusCode.RequestTimeout
        FailureCategory.CANCELLED -> HttpStatusCode.RequestTimeout
        FailureCategory.REJECTED -> HttpStatusCode.BadRequest
        FailureCategory.LIMIT_EXCEEDED -> HttpStatusCode.PayloadTooLarge
        FailureCategory.PROTOCOL -> HttpStatusCode.BadRequest
        FailureCategory.PERMISSION_DENIED -> HttpStatusCode.Forbidden
        FailureCategory.MODEL_UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
        FailureCategory.INTERNAL -> HttpStatusCode.InternalServerError
    }
    call.respond(status, ErrorResponse(
        error = error.message,
        category = error.category.name,
        endpoint_id = error.endpointId?.value,
    ))
}

private suspend fun respondError(call: ApplicationCall, error: SensesError) {
    val status = when (error) {
        is SensesError.Unavailable -> HttpStatusCode.NotFound
        is SensesError.Disconnected -> HttpStatusCode.Gone
        is SensesError.Timeout -> HttpStatusCode.RequestTimeout
        is SensesError.Cancelled -> HttpStatusCode.RequestTimeout
        is SensesError.Rejected -> HttpStatusCode.BadRequest
        is SensesError.LimitExceeded -> HttpStatusCode.PayloadTooLarge
        is SensesError.Protocol -> HttpStatusCode.BadRequest
        is SensesError.PermissionDenied -> HttpStatusCode.Forbidden
        is SensesError.ModelUnavailable -> HttpStatusCode.ServiceUnavailable
        is SensesError.Internal -> HttpStatusCode.InternalServerError
    }
    call.respond(status, ErrorResponse(
        error = error.message ?: "unknown error",
        category = error.category.name,
    ))
}

private fun Provenance.toDto() = ProvenanceDto(
    operation_id = operationId,
    endpoint_id = endpointId.value,
    backend_name = backendName,
    backend_kind = backendKind.name,
    capability = capability.name(),
    origin = origin.name,
    fixture_id = fixtureId,
    started_at = startedAt,
    completed_at = completedAt,
)

private fun SemanticListenResult.toListenResponse(raw: Boolean): ListenResponse {
    val fmt = rawAudioFormat
    return ListenResponse(
        transcript = transcript,
        confidence = confidence,
        language = language,
        audio_base64 = if (raw && rawAudio != null) Base64.getEncoder().encodeToString(rawAudio) else null,
        format = fmt?.let {
            AudioFormatDto(
                sample_rate = it.sampleRate,
                bits_per_sample = it.bitDepth,
                channels = it.channels,
                encoding = it.encoding,
                mime = it.mime,
            )
        } ?: rawAudioProvenance.mediaFormat?.let {
            AudioFormatDto(
                sample_rate = it.sampleRate ?: 0,
                bits_per_sample = 0,
                channels = it.channels ?: 0,
                encoding = it.encoding,
                mime = it.mime,
            )
        },
        duration_millis = rawAudioProvenance.mediaFormat?.durationMillis,
        provenance = rawAudioProvenance.toDto(),
        transcription_provenance = transcriptionProvenance.toDto(),
    )
}

private fun SemanticLookResult.toLookResponse(raw: Boolean): LookResponse {
    val fmt = rawImageFormat
    return LookResponse(
        description = description,
        confidence = confidence,
        objects = objects,
        image_base64 = if (raw && rawImage != null) Base64.getEncoder().encodeToString(rawImage) else null,
        format = fmt?.let {
            ImageFormatDto(
                encoding = it.encoding,
                mime = it.mime,
                width = it.width,
                height = it.height,
            )
        } ?: rawImageProvenance.mediaFormat?.let {
            ImageFormatDto(
                encoding = it.encoding,
                mime = it.mime,
                width = it.width ?: 0,
                height = it.height ?: 0,
            )
        },
        is_raw = isRaw,
        provenance = rawImageProvenance.toDto(),
        observation_provenance = observationProvenance.toDto(),
    )
}

private fun parseInteractionType(s: String): InteractionType? = when (s.uppercase()) {
    "TAP_SINGLE" -> InteractionType.TAP_SINGLE
    "TAP_DOUBLE" -> InteractionType.TAP_DOUBLE
    "TAP_TRIPLE" -> InteractionType.TAP_TRIPLE
    "BUTTON_SINGLE" -> InteractionType.BUTTON_SINGLE
    "BUTTON_DOUBLE" -> InteractionType.BUTTON_DOUBLE
    "BUTTON_LONG" -> InteractionType.BUTTON_LONG
    "APPROVAL" -> InteractionType.APPROVAL
    "SELECTION" -> InteractionType.SELECTION
    "TEXT_ENTRY" -> InteractionType.TEXT_ENTRY
    else -> null
}
