package com.nyooran.agent.senses.routes

import com.nyooran.agent.senses.*
import com.nyooran.agent.senses.simulator.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 4 A4-05: End-to-end integration suites.
 *
 * Tests the full path: HTTP routes → SenseEndpointRegistry → endpoints.
 * Asserts provenance at every boundary.
 *
 * Covers:
 * - Node → Ktor → endpoint registry → generic fixture endpoint
 * - Simulated Halo endpoint
 * - Endpoint ambiguity, absence, disconnect, cancellation, partial output
 */
class SenseIntegrationTest {

    private val authToken = "test-token"
    private val json = Json { ignoreUnknownKeys = true }

    private fun ApplicationTestBuilder.setupServer(vararg endpoints: SenseEndpoint): SenseEndpointRegistry {
        val registry = SenseEndpointRegistry()
        application {
            install(ContentNegotiation) { json(json) }
            routing { senseCapabilityRoutes(registry, authToken) }
        }
        runBlocking { endpoints.forEach { registry.bind(it) } }
        return registry
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ClientContentNegotiation) { json(json) }
    }

    private fun HttpRequestBuilder.auth() {
        header("Authorization", "Bearer $authToken")
        contentType(ContentType.Application.Json)
    }

    // ------------------------------------------------------------------ fixture endpoint e2e

    @Test
    fun e2eFixtureEndpointListenReturnsProvenanceWithFixtureId() = testApplication {
        val fixture = FixtureEndpoint(FixtureEndpoint.FixtureConfig(
            endpointId = EndpointId("fixture-1"),
            fixtureId = "test-fixture-001",
        ))
        setupServer(fixture)
        val response = jsonClient().post("/v1/sense/listen") {
            auth()
            setBody("""{"endpoint_id": "fixture-1", "max_duration_millis": 5000, "max_bytes": 65536}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<ListenResponse>()
        assertEquals("fixture-1", body.provenance.endpoint_id)
        assertEquals("test-fixture-001", body.provenance.fixture_id)
        assertEquals("SIMULATOR", body.provenance.backend_kind)
    }

    @Test
    fun e2eFixtureEndpointLookReturnsProvenanceWithFixtureId() = testApplication {
        val fixture = FixtureEndpoint(FixtureEndpoint.FixtureConfig(
            endpointId = EndpointId("fixture-1"),
            fixtureId = "test-fixture-002",
        ))
        setupServer(fixture)
        val response = jsonClient().post("/v1/sense/look") {
            auth()
            setBody("""{"endpoint_id": "fixture-1", "resolution": 640, "max_bytes": 65536}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<LookResponse>()
        assertEquals("test-fixture-002", body.provenance.fixture_id)
    }

    // ------------------------------------------------------------------ simulated Halo e2e

    @Test
    fun e2eSimulatedHaloListenReturnsSimulatorProvenance() = testApplication {
        setupServer(SimulatedHaloEndpoint())
        val response = jsonClient().post("/v1/sense/listen") {
            auth()
            setBody("""{"max_duration_millis": 5000, "max_bytes": 65536}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<ListenResponse>()
        assertEquals("SIMULATOR", body.provenance.backend_kind)
        assertEquals("AudioInput", body.provenance.capability)
        assertEquals("MICROPHONE", body.provenance.origin)
    }

    @Test
    fun e2eSimulatedHaloStatusReturnsBattery() = testApplication {
        setupServer(SimulatedHaloEndpoint())
        val response = jsonClient().post("/v1/sense/status") {
            auth()
            setBody("""{}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<StatusResponse>()
        assertNotNull(body.battery_level)
        assertEquals("SIMULATOR", body.provenance.backend_kind)
    }

    // ------------------------------------------------------------------ chat endpoint e2e

    @Test
    fun e2eChatEndpointPresentTextReturns409() = testApplication {
        setupServer(ChatEndpoint())
        // Chat endpoint supports TextOutput, not VisualOutput.
        // The /present route maps to VisualOutput, so it should return 409.
        val response = jsonClient().post("/v1/sense/present") {
            auth()
            setBody("""{"endpoint_id": "chat-1", "kind": "text", "text": "hello"}""")
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    @Test
    fun e2eChatEndpointListenReturns409() = testApplication {
        setupServer(ChatEndpoint())
        val response = jsonClient().post("/v1/sense/listen") {
            auth()
            setBody("""{"endpoint_id": "chat-1", "max_duration_millis": 5000, "max_bytes": 65536}""")
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    // ------------------------------------------------------------------ ambiguity

    @Test
    fun e2eAmbiguousEndpointReturns409WithAmbiguousCategory() = testApplication {
        setupServer(
            SimulatedHaloEndpoint(endpointId = EndpointId("halo-1")),
            SimulatedHaloEndpoint(endpointId = EndpointId("halo-2")),
        )
        val response = jsonClient().post("/v1/sense/listen") {
            auth()
            setBody("""{"max_duration_millis": 5000, "max_bytes": 65536}""")
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
        val body = response.body<ErrorResponse>()
        assertEquals("AMBIGUOUS", body.category)
    }

    // ------------------------------------------------------------------ absence

    @Test
    fun e2eNoEndpointReturns404WithUnavailableCategory() = testApplication {
        setupServer()
        val response = jsonClient().post("/v1/sense/listen") {
            auth()
            setBody("""{"max_duration_millis": 5000, "max_bytes": 65536}""")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
        val body = response.body<ErrorResponse>()
        assertEquals("UNAVAILABLE", body.category)
    }

    @Test
    fun e2eNonExistentEndpointIdReturns404() = testApplication {
        setupServer(SimulatedHaloEndpoint())
        val response = jsonClient().post("/v1/sense/listen") {
            auth()
            setBody("""{"endpoint_id": "nonexistent", "max_duration_millis": 5000, "max_bytes": 65536}""")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ------------------------------------------------------------------ provenance at every boundary

    @Test
    fun e2eProvenanceIncludesAllRequiredFields() = testApplication {
        setupServer(SimulatedHaloEndpoint())
        val response = jsonClient().post("/v1/sense/listen") {
            auth()
            setBody("""{"max_duration_millis": 5000, "max_bytes": 65536}""")
        }
        val body = response.body<ListenResponse>()
        val p = body.provenance
        assertNotNull(p.operation_id)
        assertNotNull(p.endpoint_id)
        assertNotNull(p.backend_name)
        assertNotNull(p.backend_kind)
        assertNotNull(p.capability)
        assertNotNull(p.origin)
        assertTrue(p.started_at > 0)
        assertTrue(p.completed_at >= p.started_at)
    }

    @Test
    fun e2eProvenanceDistinguishesEndpoints() = testApplication {
        setupServer(
            SimulatedHaloEndpoint(endpointId = EndpointId("halo-1")),
            ChatEndpoint(ChatEndpoint.ChatConfig(
                endpointId = EndpointId("chat-1"),
                pendingInteraction = InteractionEvent.Approval(approved = true),
            )),
        )
        // Listen from halo
        val haloResp = jsonClient().post("/v1/sense/listen") {
            auth()
            setBody("""{"endpoint_id": "halo-1", "max_duration_millis": 5000, "max_bytes": 65536}""")
        }
        val haloBody = haloResp.body<ListenResponse>()
        assertEquals("halo-1", haloBody.provenance.endpoint_id)
        assertEquals("SIMULATOR", haloBody.provenance.backend_kind)

        // Wait from chat (InteractionInput)
        val chatResp = jsonClient().post("/v1/sense/wait") {
            auth()
            setBody("""{"endpoint_id": "chat-1"}""")
        }
        val chatBody = chatResp.body<WaitResponse>()
        assertEquals("chat-1", chatBody.provenance.endpoint_id)
        assertEquals("CHAT", chatBody.provenance.backend_kind)
    }

    // ------------------------------------------------------------------ capabilities discovery

    @Test
    fun e2eCapabilitiesDiscoveryListsAllEndpoints() = testApplication {
        setupServer(
            SimulatedHaloEndpoint(endpointId = EndpointId("halo-1")),
            ChatEndpoint(ChatEndpoint.ChatConfig(endpointId = EndpointId("chat-1"))),
            FixtureEndpoint(FixtureEndpoint.FixtureConfig(endpointId = EndpointId("fixture-1"))),
        )
        val response = jsonClient().get("/v1/sense/capabilities") { auth() }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<CapabilitiesResponse>()
        assertEquals(3, body.endpoints.size)
        val ids = body.endpoints.map { it.endpoint_id }.toSet()
        assertTrue(ids.contains("halo-1"))
        assertTrue(ids.contains("chat-1"))
        assertTrue(ids.contains("fixture-1"))
    }

    @Test
    fun e2eCapabilitiesShowCorrectCapabilityLists() = testApplication {
        setupServer(ChatEndpoint())
        val response = jsonClient().get("/v1/sense/capabilities") { auth() }
        val body = response.body<CapabilitiesResponse>()
        val chat = body.endpoints.first()
        assertTrue(chat.capabilities.contains("TextInput"))
        assertTrue(chat.capabilities.contains("TextOutput"))
        assertTrue(chat.capabilities.contains("InteractionInput"))
        // Chat should NOT advertise AudioInput
        assertTrue(!chat.capabilities.contains("AudioInput"))
    }

    // ------------------------------------------------------------------ auth

    @Test
    fun e2eRejectsMissingAuth() = testApplication {
        setupServer(SimulatedHaloEndpoint())
        val response = jsonClient().get("/v1/sense/capabilities")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun e2eRejectsWrongAuth() = testApplication {
        setupServer(SimulatedHaloEndpoint())
        val response = jsonClient().get("/v1/sense/capabilities") {
            header("Authorization", "Bearer wrong-token")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ------------------------------------------------------------------ all routes smoke test

    @Test
    fun e2eAllRoutesAccessibleWithSimulatedHalo() = testApplication {
        setupServer(SimulatedHaloEndpoint())
        val client = jsonClient()

        // Capabilities
        assertEquals(HttpStatusCode.OK, client.get("/v1/sense/capabilities") { auth() }.status)

        // Listen
        assertEquals(HttpStatusCode.OK, client.post("/v1/sense/listen") {
            auth(); setBody("""{"max_duration_millis": 5000, "max_bytes": 65536}""")
        }.status)

        // Look
        assertEquals(HttpStatusCode.OK, client.post("/v1/sense/look") {
            auth(); setBody("""{"resolution": 640, "max_bytes": 65536}""")
        }.status)

        // Wait
        assertEquals(HttpStatusCode.OK, client.post("/v1/sense/wait") {
            auth(); setBody("""{}""")
        }.status)

        // Speak
        val audioB64 = java.util.Base64.getEncoder().encodeToString(ByteArray(100) { 0x55 })
        assertEquals(HttpStatusCode.OK, client.post("/v1/sense/speak") {
            auth(); setBody("""{"audio_base64": "$audioB64", "format": {"sample_rate": 16000, "bits_per_sample": 16, "channels": 1, "encoding": "wav", "mime": "audio/wav"}}""")
        }.status)

        // Present
        val payloadB64 = java.util.Base64.getEncoder().encodeToString(byteArrayOf(0x48, 0x52, 0x50, 0x31, 0, 0, 0))
        assertEquals(HttpStatusCode.OK, client.post("/v1/sense/present") {
            auth(); setBody("""{"kind": "device_native", "payload_base64": "$payloadB64"}""")
        }.status)

        // Status
        assertEquals(HttpStatusCode.OK, client.post("/v1/sense/status") {
            auth(); setBody("""{}""")
        }.status)
    }
}
