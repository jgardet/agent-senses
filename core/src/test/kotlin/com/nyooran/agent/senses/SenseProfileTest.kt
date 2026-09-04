package com.nyooran.agent.senses

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SenseProfileTest {

    @Test
    fun profileSupportsListedCapabilities() {
        val profile = SenseProfile(
            endpointId = EndpointId("halo-1"),
            backendName = "Halo",
            backendKind = BackendKind.PHYSICAL,
            state = EndpointState.READY,
            capabilities = setOf(
                SenseCapability.AudioInput,
                SenseCapability.AudioOutput,
                SenseCapability.ImageInput,
                SenseCapability.VisualOutput,
                SenseCapability.InteractionInput,
                SenseCapability.StatusInput,
            ),
            limits = mapOf(
                SenseCapability.AudioInput to SenseLimits(maxDurationMillis = 60_000, maxBytes = 1_048_576),
            ),
            concurrency = ConcurrencyProfile(
                resourceDomains = mapOf(
                    SenseCapability.AudioInput to "audio",
                    SenseCapability.AudioOutput to "audio",
                    SenseCapability.ImageInput to "camera",
                    SenseCapability.VisualOutput to "display",
                ),
                explicitConflicts = setOf(
                    SenseCapability.AudioInput to SenseCapability.ImageInput,
                ),
            ),
        )

        assertTrue(profile.supports(SenseCapability.AudioInput))
        assertTrue(profile.supports(SenseCapability.VisualOutput))
        assertFalse(profile.supports(SenseCapability.TextInput))
        assertFalse(profile.supports(SenseCapability.TextOutput))
    }

    @Test
    fun limitsForReturnsNullForUnsupported() {
        val profile = SenseProfile(
            endpointId = EndpointId("chat-1"),
            backendName = "Chat",
            backendKind = BackendKind.CHAT,
            state = EndpointState.READY,
            capabilities = setOf(SenseCapability.TextInput, SenseCapability.TextOutput),
            limits = emptyMap(),
            concurrency = ConcurrencyProfile(),
        )
        assertNull(profile.limitsFor(SenseCapability.AudioInput))
        assertNull(profile.limitsFor(SenseCapability.TextOutput))
    }

    @Test
    fun concurrencySameDomainDoesNotOverlap() {
        val profile = SenseProfile(
            endpointId = EndpointId("halo-1"),
            backendName = "Halo",
            backendKind = BackendKind.PHYSICAL,
            state = EndpointState.READY,
            capabilities = emptySet(),
            limits = emptyMap(),
            concurrency = ConcurrencyProfile(
                resourceDomains = mapOf(
                    SenseCapability.AudioInput to "audio",
                    SenseCapability.AudioOutput to "audio",
                ),
            ),
        )
        // Same domain → serialized
        assertFalse(profile.canOverlap(SenseCapability.AudioInput, SenseCapability.AudioOutput))
    }

    @Test
    fun concurrencyDifferentDomainsOverlap() {
        val profile = SenseProfile(
            endpointId = EndpointId("halo-1"),
            backendName = "Halo",
            backendKind = BackendKind.PHYSICAL,
            state = EndpointState.READY,
            capabilities = emptySet(),
            limits = emptyMap(),
            concurrency = ConcurrencyProfile(
                resourceDomains = mapOf(
                    SenseCapability.AudioInput to "audio",
                    SenseCapability.VisualOutput to "display",
                ),
            ),
        )
        // Different domains → can overlap
        assertTrue(profile.canOverlap(SenseCapability.AudioInput, SenseCapability.VisualOutput))
    }

    @Test
    fun concurrencyExplicitConflictDoesNotOverlap() {
        val profile = SenseProfile(
            endpointId = EndpointId("halo-1"),
            backendName = "Halo",
            backendKind = BackendKind.PHYSICAL,
            state = EndpointState.READY,
            capabilities = emptySet(),
            limits = emptyMap(),
            concurrency = ConcurrencyProfile(
                resourceDomains = mapOf(
                    SenseCapability.AudioInput to "audio",
                    SenseCapability.ImageInput to "camera",
                ),
                explicitConflicts = setOf(SenseCapability.AudioInput to SenseCapability.ImageInput),
            ),
        )
        // Different domains but explicit conflict → serialized
        assertFalse(profile.canOverlap(SenseCapability.AudioInput, SenseCapability.ImageInput))
        // Symmetric
        assertFalse(profile.canOverlap(SenseCapability.ImageInput, SenseCapability.AudioInput))
    }

    @Test
    fun concurrencySameCapabilityDoesNotOverlap() {
        val profile = SenseProfile(
            endpointId = EndpointId("halo-1"),
            backendName = "Halo",
            backendKind = BackendKind.PHYSICAL,
            state = EndpointState.READY,
            capabilities = emptySet(),
            limits = emptyMap(),
            concurrency = ConcurrencyProfile(),
        )
        assertFalse(profile.canOverlap(SenseCapability.AudioInput, SenseCapability.AudioInput))
    }

    @Test
    fun chatProfileHasReducedCapabilities() {
        val profile = SenseProfile(
            endpointId = EndpointId("chat-1"),
            backendName = "Chat",
            backendKind = BackendKind.CHAT,
            state = EndpointState.READY,
            capabilities = setOf(
                SenseCapability.TextInput,
                SenseCapability.TextOutput,
                SenseCapability.InteractionInput,
                SenseCapability.ImageInput,  // attachment picker
            ),
            limits = emptyMap(),
            concurrency = ConcurrencyProfile(
                resourceDomains = mapOf(
                    SenseCapability.TextInput to "ui",
                    SenseCapability.TextOutput to "ui",
                    SenseCapability.InteractionInput to "ui",
                ),
            ),
        )
        assertTrue(profile.supports(SenseCapability.TextInput))
        assertTrue(profile.supports(SenseCapability.TextOutput))
        assertFalse(profile.supports(SenseCapability.AudioInput))
        assertFalse(profile.supports(SenseCapability.AudioOutput))
        assertFalse(profile.supports(SenseCapability.StatusInput))
    }

    @Test
    fun simulatorProfileMarksBackendKind() {
        val profile = SenseProfile(
            endpointId = EndpointId("sim-1"),
            backendName = "Simulator",
            backendKind = BackendKind.SIMULATOR,
            state = EndpointState.READY,
            capabilities = setOf(SenseCapability.AudioInput, SenseCapability.StatusInput),
            limits = emptyMap(),
            concurrency = ConcurrencyProfile(),
        )
        assertEquals(BackendKind.SIMULATOR, profile.backendKind)
    }
}
