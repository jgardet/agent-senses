package com.nyooran.agent.senses.simulator

import com.nyooran.agent.senses.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FixtureEndpointTest {

    private fun endpoint(config: FixtureEndpoint.FixtureConfig = FixtureEndpoint.FixtureConfig()) =
        FixtureEndpoint(config)

    @Test
    fun defaultProfileHasAllCapabilities() {
        val ep = endpoint()
        assertEquals(8, ep.profile.capabilities.size)
    }

    @Test
    fun customCapabilitiesAreRespected() {
        val ep = endpoint(FixtureEndpoint.FixtureConfig(
            capabilities = setOf(SenseCapability.TextInput, SenseCapability.TextOutput),
        ))
        assertTrue(ep.profile.supports(SenseCapability.TextInput))
        assertFalse(ep.profile.supports(SenseCapability.AudioInput))
    }

    @Test
    fun connectDisconnectWorks() = runTest {
        val ep = endpoint()
        ep.connect()
        ep.disconnect()
    }

    @Test
    fun audioInputReturnsFixture() = runTest {
        val ep = endpoint(FixtureEndpoint.FixtureConfig(
            audioFixture = ByteArray(100) { 0x42 },
            fixtureId = "audio-test",
        ))
        ep.connect()
        val result = ep.audioInput(AudioInputRequest(maxDurationMillis = 100, maxBytes = 1024))
        assertEquals(100, result.audio.size)
        assertEquals(0x42.toByte(), result.audio[0])
        assertEquals("audio-test", result.provenance.fixtureId)
    }

    @Test
    fun imageInputReturnsFixture() = runTest {
        val ep = endpoint(FixtureEndpoint.FixtureConfig(
            imageFixture = byteArrayOf(1, 2, 3),
        ))
        ep.connect()
        val result = ep.imageInput(ImageInputRequest(resolution = 640, qualityIndex = 0, maxBytes = 65536))
        assertEquals(3, result.image.size)
    }

    @Test
    fun textInputReturnsFixture() = runTest {
        val ep = endpoint(FixtureEndpoint.FixtureConfig(textFixture = "hello world"))
        ep.connect()
        val result = ep.textInput(TextInputRequest())
        assertEquals("hello world", result.text)
    }

    @Test
    fun statusInputReturnsFixture() = runTest {
        val ep = endpoint(FixtureEndpoint.FixtureConfig(
            statusFixture = EndpointStatus(batteryLevel = 30, batteryCharging = true),
        ))
        ep.connect()
        val result = ep.statusInput(StatusInputRequest())
        assertEquals(30, result.status.batteryLevel)
        assertEquals(true, result.status.batteryCharging)
    }

    @Test
    fun interactionInputReturnsFixture() = runTest {
        val ep = endpoint(FixtureEndpoint.FixtureConfig(
            interactionFixture = InteractionEvent.Button(ButtonGesture.LONG),
        ))
        ep.connect()
        val result = ep.interactionInput(InteractionInputRequest())
        assertTrue(result.event is InteractionEvent.Button)
        assertEquals(ButtonGesture.LONG, (result.event as InteractionEvent.Button).gesture)
    }

    @Test
    fun unsupportedCapabilityThrowsUnavailable() = runTest {
        val ep = endpoint(FixtureEndpoint.FixtureConfig(
            capabilities = setOf(SenseCapability.TextInput),
        ))
        ep.connect()
        assertFailsWith<SensesError.Unavailable> {
            ep.audioInput(AudioInputRequest(maxDurationMillis = 100, maxBytes = 1024))
        }
    }

    @Test
    fun configuredFailureIsThrown() = runTest {
        val ep = endpoint(FixtureEndpoint.FixtureConfig(
            failureForCapability = mapOf(
                SenseCapability.AudioInput to SenseFailure(FailureCategory.TIMEOUT, "simulated timeout")
            ),
        ))
        ep.connect()
        assertFailsWith<SensesError.Internal> {
            ep.audioInput(AudioInputRequest(maxDurationMillis = 100, maxBytes = 1024))
        }
    }

    @Test
    fun provenanceAlwaysHasFixtureId() = runTest {
        val ep = endpoint(FixtureEndpoint.FixtureConfig(fixtureId = "stable-fixture-42"))
        ep.connect()
        val audioResult = ep.audioInput(AudioInputRequest(maxDurationMillis = 100, maxBytes = 1024))
        assertEquals("stable-fixture-42", audioResult.provenance.fixtureId)
        val statusResult = ep.statusInput(StatusInputRequest())
        assertEquals("stable-fixture-42", statusResult.provenance.fixtureId)
    }

    @Test
    fun provenanceBackendKindMatchesConfig() = runTest {
        val ep = endpoint(FixtureEndpoint.FixtureConfig(backendKind = BackendKind.CHAT))
        ep.connect()
        val result = ep.textOutput(TextOutputRequest(TextContent("hi")))
        assertEquals(BackendKind.CHAT, result.provenance.backendKind)
    }

    @Test
    fun operationIdsAreUnique() = runTest {
        val ep = endpoint()
        ep.connect()
        val r1 = ep.statusInput(StatusInputRequest())
        val r2 = ep.statusInput(StatusInputRequest())
        assertFalse(r1.provenance.operationId == r2.provenance.operationId)
    }

    @Test
    fun worksInRegistry() = runTest {
        val registry = SenseEndpointRegistry()
        val ep = endpoint()
        registry.bind(ep)
        val result = registry.resolve(SenseCapability.AudioInput)
        assertTrue(result is ResolveResult.Resolved)
        registry.unbindAll()
    }
}
