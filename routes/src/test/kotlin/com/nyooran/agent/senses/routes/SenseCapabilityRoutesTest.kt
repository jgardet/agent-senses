package com.nyooran.agent.senses.routes

import com.nyooran.agent.senses.*
import com.nyooran.agent.senses.simulator.SimulatedHaloEndpoint
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SenseCapabilityRoutesTest {

    private val authToken = "test-token"
    private val json = Json { ignoreUnknownKeys = true }

    private fun ApplicationTestBuilder.setupServer(): SenseEndpointRegistry {
        val registry = SenseEndpointRegistry()
        application {
            install(ContentNegotiation) { json(json) }
            routing { senseCapabilityRoutes(registry, authToken) }
        }
        return registry
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ClientContentNegotiation) { json(json) }
    }

    private fun HttpRequestBuilder.auth() {
        header("Authorization", "Bearer $authToken")
        contentType(ContentType.Application.Json)
    }

    // ------------------------------------------------------------------ capabilities

    @Test
    fun capabilitiesReturnsAllEndpoints() = testApplication {
        val registry = setupServer()
        kotlinx.coroutines.runBlocking {
            registry.bind(SimulatedHaloEndpoint())
            registry.bind(ChatEndpoint())
        }
        val response = jsonClient().get("/v1/sense/capabilities") { auth() }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<CapabilitiesResponse>()
        assertEquals(2, body.endpoints.size)
        assertTrue(body.endpoints.any { it.backend_kind == "SIMULATOR" })
        assertTrue(body.endpoints.any { it.backend_kind == "CHAT" })
    }

    @Test
    fun capabilitiesRequiresAuth() = testApplication {
        setupServer()
        val response = jsonClient().get("/v1/sense/capabilities")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ------------------------------------------------------------------ listen

    @Test
    fun listenReturnsAudioWithProvenance() = testApplication {
        val registry = setupServer()
        kotlinx.coroutines.runBlocking { registry.bind(SimulatedHaloEndpoint()) }
        val response = jsonClient().post("/v1/sense/listen") {
            auth()
            setBody("""{"max_duration_millis": 5000, "max_bytes": 65536}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<ListenResponse>()
        assertNotNull(body.audio_base64)
        assertEquals("SIMULATOR", body.provenance.backend_kind)
        assertEquals("AudioInput", body.provenance.capability)
    }

    @Test
    fun listenRejectsInvalidTimeout() = testApplication {
        val registry = setupServer()
        kotlinx.coroutines.runBlocking { registry.bind(SimulatedHaloEndpoint()) }
        val response = jsonClient().post("/v1/sense/listen") {
            auth()
            setBody("""{"max_duration_millis": 0, "max_bytes": 1024}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun listenReturnsNotFoundWhenNoEndpoint() = testApplication {
        setupServer()
        val response = jsonClient().post("/v1/sense/listen") {
            auth()
            setBody("""{"max_duration_millis": 5000, "max_bytes": 65536}""")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ------------------------------------------------------------------ look

    @Test
    fun lookReturnsImageWithProvenance() = testApplication {
        val registry = setupServer()
        kotlinx.coroutines.runBlocking { registry.bind(SimulatedHaloEndpoint()) }
        val response = jsonClient().post("/v1/sense/look") {
            auth()
            setBody("""{"resolution": 640, "quality_index": 0, "max_bytes": 65536}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<LookResponse>()
        assertEquals("SIMULATOR", body.provenance.backend_kind)
        assertEquals("ImageInput", body.provenance.capability)
    }

    // ------------------------------------------------------------------ wait

    @Test
    fun waitReturnsInteractionEvent() = testApplication {
        val registry = setupServer()
        kotlinx.coroutines.runBlocking { registry.bind(SimulatedHaloEndpoint()) }
        val response = jsonClient().post("/v1/sense/wait") {
            auth()
            setBody("""{}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<WaitResponse>()
        assertEquals("tap", body.event_type)
        assertEquals("SIMULATOR", body.provenance.backend_kind)
    }

    // ------------------------------------------------------------------ speak

    @Test
    fun speakReturnsProvenance() = testApplication {
        val registry = setupServer()
        kotlinx.coroutines.runBlocking { registry.bind(SimulatedHaloEndpoint()) }
        val audioBase64 = java.util.Base64.getEncoder().encodeToString(ByteArray(100) { 0x55 })
        val response = jsonClient().post("/v1/sense/speak") {
            auth()
            setBody("""{"audio_base64": "$audioBase64", "format": {"sample_rate": 16000, "bits_per_sample": 16, "channels": 1, "encoding": "wav", "mime": "audio/wav"}}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<SpeakResponse>()
        assertEquals("AudioOutput", body.provenance.capability)
    }

    @Test
    fun speakRejectsMissingAudio() = testApplication {
        val registry = setupServer()
        kotlinx.coroutines.runBlocking { registry.bind(SimulatedHaloEndpoint()) }
        val response = jsonClient().post("/v1/sense/speak") {
            auth()
            setBody("""{}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ------------------------------------------------------------------ present

    @Test
    fun presentReturnsProvenance() = testApplication {
        val registry = setupServer()
        kotlinx.coroutines.runBlocking { registry.bind(SimulatedHaloEndpoint()) }
        val payloadBase64 = java.util.Base64.getEncoder().encodeToString(
            byteArrayOf(0x48, 0x52, 0x50, 0x31, 0, 0, 0)
        )
        val response = jsonClient().post("/v1/sense/present") {
            auth()
            setBody("""{"kind": "device_native", "payload_base64": "$payloadBase64"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<PresentResponse>()
        assertEquals("VisualOutput", body.provenance.capability)
    }

    @Test
    fun presentRejectsInvalidKind() = testApplication {
        val registry = setupServer()
        kotlinx.coroutines.runBlocking { registry.bind(SimulatedHaloEndpoint()) }
        val response = jsonClient().post("/v1/sense/present") {
            auth()
            setBody("""{"kind": "hologram"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ------------------------------------------------------------------ status

    @Test
    fun statusReturnsBattery() = testApplication {
        val registry = setupServer()
        kotlinx.coroutines.runBlocking { registry.bind(SimulatedHaloEndpoint()) }
        val response = jsonClient().post("/v1/sense/status") {
            auth()
            setBody("""{}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<StatusResponse>()
        assertNotNull(body.battery_level)
        assertEquals("SIMULATOR", body.provenance.backend_kind)
    }

    // ------------------------------------------------------------------ endpoint targeting

    @Test
    fun listenWithEndpointIdTargetsSpecificEndpoint() = testApplication {
        val registry = setupServer()
        val halo = SimulatedHaloEndpoint(endpointId = EndpointId("halo-1"))
        val chat = ChatEndpoint()
        kotlinx.coroutines.runBlocking {
            registry.bind(halo)
            registry.bind(chat)
        }
        val response = jsonClient().post("/v1/sense/listen") {
            auth()
            setBody("""{"endpoint_id": "halo-1", "max_duration_millis": 5000, "max_bytes": 65536}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<ListenResponse>()
        assertEquals("halo-1", body.provenance.endpoint_id)
    }

    @Test
    fun listenWithNonExistentEndpointIdReturns404() = testApplication {
        val registry = setupServer()
        kotlinx.coroutines.runBlocking { registry.bind(SimulatedHaloEndpoint()) }
        val response = jsonClient().post("/v1/sense/listen") {
            auth()
            setBody("""{"endpoint_id": "nonexistent", "max_duration_millis": 5000, "max_bytes": 65536}""")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun listenWithUnsupportedCapabilityReturns409() = testApplication {
        val registry = setupServer()
        kotlinx.coroutines.runBlocking { registry.bind(ChatEndpoint()) }
        val response = jsonClient().post("/v1/sense/listen") {
            auth()
            setBody("""{"endpoint_id": "chat-1", "max_duration_millis": 5000, "max_bytes": 65536}""")
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
    }
}
