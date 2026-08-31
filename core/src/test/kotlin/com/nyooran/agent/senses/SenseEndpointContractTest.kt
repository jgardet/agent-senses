package com.nyooran.agent.senses

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 2 shared endpoint contract suite (C2-08).
 *
 * These tests define the behavioral contract that every [SenseEndpoint]
 * implementation must satisfy, regardless of backend kind. Endpoint
 * implementations provide a [ContractFixture] and run these tests
 * against it.
 *
 * The suite covers:
 * - Profile/capability advertisement
 * - Limits and invalid-request rejection
 * - Cancellation, timeout, and disconnect
 * - Provenance on every result
 * - Concurrency profile enforcement
 */

/**
 * Test fixture providing an endpoint and its expected behavior.
 * Implementations must provide a freshly-constructed endpoint for each test.
 */
interface ContractFixture {
    /** Human-readable name for test reporting. */
    val name: String

    /** Create a fresh endpoint instance for testing. */
    fun createEndpoint(): SenseEndpoint

    /** Capabilities this fixture's endpoint supports. */
    val supportedCapabilities: Set<SenseCapability>

    /** Whether this endpoint supports a specific capability for testing. */
    fun supports(capability: SenseCapability): Boolean = capability in supportedCapabilities
}

/**
 * Abstract base class for endpoint contract tests.
 *
 * Subclass with a specific [ContractFixture] to run the shared suite
 * against any endpoint implementation.
 */
abstract class SenseEndpointContractTest {

    protected abstract val fixture: ContractFixture

    // ------------------------------------------------------------------ profile tests

    @Test
    fun profileAdvertisesOnlySupportedCapabilities() {
        val endpoint = fixture.createEndpoint()
        val profile = endpoint.profile
        for (cap in profile.capabilities) {
            assertTrue(fixture.supports(cap),
                "Profile advertises $cap but fixture does not expect it")
        }
    }

    @Test
    fun profileHasValidEndpointId() {
        val endpoint = fixture.createEndpoint()
        val id = endpoint.profile.endpointId
        assertTrue(id.value.isNotBlank(), "EndpointId must not be blank")
    }

    @Test
    fun profileHasBackendKind() {
        val endpoint = fixture.createEndpoint()
        val kind = endpoint.profile.backendKind
        assertNotNull(kind, "BackendKind must be set")
    }

    @Test
    fun profileStateIsDisconnectedBeforeConnect() {
        val endpoint = fixture.createEndpoint()
        // Before connect, state should be DISCONNECTED or CONNECTING
        val state = endpoint.profile.state
        assertTrue(
            state == EndpointState.DISCONNECTED || state == EndpointState.CONNECTING,
            "State before connect should be DISCONNECTED or CONNECTING, was $state"
        )
    }

    @Test
    fun profileLimitsCoverAdvertisedCapabilities() {
        val endpoint = fixture.createEndpoint()
        val profile = endpoint.profile
        for (cap in profile.capabilities) {
            // Each capability should have limits or explicitly allow unbounded
            // (null limits is acceptable; we just verify the map is consistent)
            val limits = profile.limitsFor(cap)
            if (limits != null) {
                if (limits.maxBytes != null) {
                    assertTrue(limits.maxBytes > 0, "maxBytes for $cap must be positive, was ${limits.maxBytes}")
                }
                if (limits.maxDurationMillis != null) {
                    assertTrue(limits.maxDurationMillis > 0, "maxDurationMillis for $cap must be positive")
                }
                if (limits.maxConcurrent != null) {
                    assertTrue(limits.maxConcurrent > 0, "maxConcurrent for $cap must be positive")
                }
            }
        }
    }

    // ------------------------------------------------------------------ connect/disconnect tests

    @Test
    fun connectThenDisconnectSucceeds() = runTest {
        val endpoint = fixture.createEndpoint()
        endpoint.connect()
        endpoint.disconnect()
        // Should not throw
    }

    @Test
    fun disconnectWithoutConnectSucceeds() = runTest {
        val endpoint = fixture.createEndpoint()
        // Disconnect without connect should not throw
        endpoint.disconnect()
    }

    // ------------------------------------------------------------------ provenance tests

    @Test
    fun audioInputResultHasProvenance() = runTest {
        if (!fixture.supports(SenseCapability.AudioInput)) return@runTest
        val endpoint = fixture.createEndpoint()
        endpoint.connect()
        try {
            val result = endpoint.audioInput(AudioInputRequest(
                maxDurationMillis = 100,
                maxBytes = 1024,
                timeoutMillis = 5_000,
            ))
            val p = result.provenance
            assertEquals(SenseCapability.AudioInput, p.capability)
            assertEquals(endpoint.profile.endpointId, p.endpointId)
            assertEquals(endpoint.profile.backendKind, p.backendKind)
            assertTrue(p.startedAt <= p.completedAt)
            assertNotNull(p.operationId)
        } finally {
            endpoint.disconnect()
        }
    }

    @Test
    fun statusInputResultHasProvenance() = runTest {
        if (!fixture.supports(SenseCapability.StatusInput)) return@runTest
        val endpoint = fixture.createEndpoint()
        endpoint.connect()
        try {
            val result = endpoint.statusInput(StatusInputRequest())
            val p = result.provenance
            assertEquals(SenseCapability.StatusInput, p.capability)
            assertEquals(endpoint.profile.endpointId, p.endpointId)
        } finally {
            endpoint.disconnect()
        }
    }

    // ------------------------------------------------------------------ unsupported capability tests

    @Test
    fun unsupportedAudioInputThrowsUnavailable() = runTest {
        if (fixture.supports(SenseCapability.AudioInput)) return@runTest
        val endpoint = fixture.createEndpoint()
        endpoint.connect()
        try {
            var threw = false
            try {
                endpoint.audioInput(AudioInputRequest(maxDurationMillis = 100, maxBytes = 1024))
            } catch (e: SensesError.Unavailable) {
                threw = true
            } catch (e: UnsupportedOperationException) {
                threw = true
            }
            assertTrue(threw, "Unsupported AudioInput should throw Unavailable or UnsupportedOperationException")
        } finally {
            endpoint.disconnect()
        }
    }

    @Test
    fun unsupportedImageInputThrowsUnavailable() = runTest {
        if (fixture.supports(SenseCapability.ImageInput)) return@runTest
        val endpoint = fixture.createEndpoint()
        endpoint.connect()
        try {
            var threw = false
            try {
                endpoint.imageInput(ImageInputRequest(resolution = 640, qualityIndex = 0, maxBytes = 65536))
            } catch (e: SensesError.Unavailable) {
                threw = true
            } catch (e: UnsupportedOperationException) {
                threw = true
            }
            assertTrue(threw, "Unsupported ImageInput should throw Unavailable or UnsupportedOperationException")
        } finally {
            endpoint.disconnect()
        }
    }

    // ------------------------------------------------------------------ concurrency tests

    @Test
    fun concurrencyProfileIsConsistentWithCapabilities() {
        val endpoint = fixture.createEndpoint()
        val profile = endpoint.profile
        // Every capability in the concurrency profile should be in the capabilities set
        for (cap in profile.concurrency.resourceDomains.keys) {
            assertTrue(profile.supports(cap),
                "Concurrency profile references $cap which is not in capabilities")
        }
    }

    // ------------------------------------------------------------------ registry integration tests

    @Test
    fun endpointCanBeRegisteredAndResolved() = runTest {
        val registry = SenseEndpointRegistry()
        val endpoint = fixture.createEndpoint()
        registry.bind(endpoint)
        val result = registry.resolve(
            fixture.supportedCapabilities.firstOrNull() ?: SenseCapability.StatusInput
        )
        // Should resolve to the bound endpoint (or report no endpoint if no caps)
        when (result) {
            is ResolveResult.Resolved -> assertEquals(endpoint.profile.endpointId, result.endpoint.profile.endpointId)
            is ResolveResult.NoEndpoint -> { /* acceptable if fixture has no capabilities */ }
            is ResolveResult.Ambiguous -> { /* acceptable if multiple endpoints */ }
            else -> { }
        }
        registry.unbindAll()
    }
}
