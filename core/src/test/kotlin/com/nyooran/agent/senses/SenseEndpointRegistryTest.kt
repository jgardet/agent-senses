package com.nyooran.agent.senses

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SenseEndpointRegistryTest {

    private fun makeProfile(
        id: String,
        kind: BackendKind = BackendKind.PHYSICAL,
        caps: Set<SenseCapability> = emptySet(),
    ) = SenseProfile(
        endpointId = EndpointId(id),
        backendName = id,
        backendKind = kind,
        state = EndpointState.READY,
        capabilities = caps,
        limits = emptyMap(),
        concurrency = ConcurrencyProfile(),
    )

    private class FakeEndpoint(override val profile: SenseProfile) : SenseEndpoint {
        var connected = false
        var disconnected = false
        override suspend fun connect() { connected = true }
        override suspend fun disconnect() { disconnected = true }
        override suspend fun audioInput(request: AudioInputRequest) = throw UnsupportedOperationException()
        override suspend fun audioOutput(request: AudioOutputRequest) = throw UnsupportedOperationException()
        override suspend fun imageInput(request: ImageInputRequest) = throw UnsupportedOperationException()
        override suspend fun visualOutput(request: VisualOutputRequest) = throw UnsupportedOperationException()
        override suspend fun textOutput(request: TextOutputRequest) = throw UnsupportedOperationException()
        override suspend fun textInput(request: TextInputRequest) = throw UnsupportedOperationException()
        override suspend fun interactionInput(request: InteractionInputRequest) = throw UnsupportedOperationException()
        override suspend fun statusInput(request: StatusInputRequest) = throw UnsupportedOperationException()
    }

    @Test
    fun bindMakesEndpointAvailable() = runTest {
        val registry = SenseEndpointRegistry()
        val endpoint = FakeEndpoint(makeProfile("halo-1", caps = setOf(SenseCapability.AudioInput)))
        registry.bind(endpoint)
        assertEquals(1, registry.endpointProfiles.value.size)
        assertEquals(endpoint, registry.get(EndpointId("halo-1")))
    }

    @Test
    fun unbindRemovesEndpoint() = runTest {
        val registry = SenseEndpointRegistry()
        val endpoint = FakeEndpoint(makeProfile("halo-1"))
        registry.bind(endpoint)
        registry.unbind(EndpointId("halo-1"))
        assertEquals(0, registry.endpointProfiles.value.size)
        assertNull(registry.get(EndpointId("halo-1")))
        assertTrue(endpoint.disconnected)
    }

    @Test
    fun bindReplacesExistingEndpoint() = runTest {
        val registry = SenseEndpointRegistry()
        val ep1 = FakeEndpoint(makeProfile("halo-1"))
        val ep2 = FakeEndpoint(makeProfile("halo-1"))
        registry.bind(ep1)
        registry.bind(ep2)
        assertEquals(1, registry.endpointProfiles.value.size)
        assertEquals(ep2, registry.get(EndpointId("halo-1")))
        assertTrue(ep1.disconnected)
    }

    @Test
    fun endpointsForCapabilityReturnsOnlySupporting() = runTest {
        val registry = SenseEndpointRegistry()
        registry.bind(FakeEndpoint(makeProfile("halo-1", caps = setOf(SenseCapability.AudioInput))))
        registry.bind(FakeEndpoint(makeProfile("chat-1", kind = BackendKind.CHAT, caps = setOf(SenseCapability.TextInput))))
        val audioEndpoints = registry.endpointsForCapability(SenseCapability.AudioInput)
        assertEquals(1, audioEndpoints.size)
        assertEquals(EndpointId("halo-1"), audioEndpoints[0].profile.endpointId)
    }

    @Test
    fun resolveSingleEndpointReturnsResolved() = runTest {
        val registry = SenseEndpointRegistry()
        val endpoint = FakeEndpoint(makeProfile("halo-1", caps = setOf(SenseCapability.AudioInput)))
        registry.bind(endpoint)
        val result = registry.resolve(SenseCapability.AudioInput)
        assertTrue(result is ResolveResult.Resolved)
        assertEquals(endpoint, result.endpoint)
    }

    @Test
    fun resolveMultipleEndpointsReturnsAmbiguous() = runTest {
        val registry = SenseEndpointRegistry()
        registry.bind(FakeEndpoint(makeProfile("halo-1", caps = setOf(SenseCapability.AudioInput))))
        registry.bind(FakeEndpoint(makeProfile("phone-1", kind = BackendKind.PHONE, caps = setOf(SenseCapability.AudioInput))))
        val result = registry.resolve(SenseCapability.AudioInput)
        assertTrue(result is ResolveResult.Ambiguous)
        assertEquals(2, result.eligible.size)
    }

    @Test
    fun resolvePrefersPreferredEndpointWhenAmbiguous() = runTest {
        val registry = SenseEndpointRegistry()
        val halo = FakeEndpoint(makeProfile("halo-1", caps = setOf(SenseCapability.AudioInput)))
        val phone = FakeEndpoint(makeProfile("phone-1", kind = BackendKind.PHONE, caps = setOf(SenseCapability.AudioInput)))
        registry.bind(halo)
        registry.bind(phone)
        registry.preferredEndpointId = EndpointId("halo-1")
        val result = registry.resolve(SenseCapability.AudioInput)
        assertTrue(result is ResolveResult.Resolved)
        assertEquals(halo, result.endpoint)
    }

    @Test
    fun resolveFallsBackToAmbiguousWhenPreferredNotEligible() = runTest {
        val registry = SenseEndpointRegistry()
        registry.bind(FakeEndpoint(makeProfile("halo-1", caps = setOf(SenseCapability.AudioInput))))
        registry.bind(FakeEndpoint(makeProfile("phone-1", kind = BackendKind.PHONE, caps = setOf(SenseCapability.AudioInput))))
        registry.bind(FakeEndpoint(makeProfile("chat-1", kind = BackendKind.CHAT, caps = setOf(SenseCapability.TextInput))))
        // Preferred endpoint does not support the requested capability.
        registry.preferredEndpointId = EndpointId("chat-1")
        val result = registry.resolve(SenseCapability.AudioInput)
        assertTrue(result is ResolveResult.Ambiguous)
        assertEquals(2, result.eligible.size)
    }

    @Test
    fun resolveExplicitTargetStillWinsOverPreference() = runTest {
        val registry = SenseEndpointRegistry()
        registry.bind(FakeEndpoint(makeProfile("halo-1", caps = setOf(SenseCapability.AudioInput))))
        registry.bind(FakeEndpoint(makeProfile("phone-1", kind = BackendKind.PHONE, caps = setOf(SenseCapability.AudioInput))))
        registry.preferredEndpointId = EndpointId("halo-1")
        val result = registry.resolve(SenseCapability.AudioInput, target = EndpointId("phone-1"))
        assertTrue(result is ResolveResult.Resolved)
        assertEquals(EndpointId("phone-1"), result.endpoint.profile.endpointId)
    }

    @Test
    fun unbindClearsPreferenceWhenPreferredRemoved() = runTest {
        val registry = SenseEndpointRegistry()
        registry.bind(FakeEndpoint(makeProfile("halo-1", caps = setOf(SenseCapability.AudioInput))))
        registry.bind(FakeEndpoint(makeProfile("phone-1", kind = BackendKind.PHONE, caps = setOf(SenseCapability.AudioInput))))
        registry.preferredEndpointId = EndpointId("halo-1")
        registry.unbind(EndpointId("halo-1"))
        assertNull(registry.preferredEndpointId)
        // Back to single-endpoint resolution.
        val result = registry.resolve(SenseCapability.AudioInput)
        assertTrue(result is ResolveResult.Resolved)
        assertEquals(EndpointId("phone-1"), result.endpoint.profile.endpointId)
    }

    @Test
    fun unbindAllClearsPreference() = runTest {
        val registry = SenseEndpointRegistry()
        registry.bind(FakeEndpoint(makeProfile("halo-1")))
        registry.preferredEndpointId = EndpointId("halo-1")
        registry.unbindAll()
        assertNull(registry.preferredEndpointId)
    }

    @Test
    fun unbindKeepsPreferenceForOtherEndpoint() = runTest {
        val registry = SenseEndpointRegistry()
        registry.bind(FakeEndpoint(makeProfile("halo-1")))
        registry.bind(FakeEndpoint(makeProfile("phone-1", kind = BackendKind.PHONE)))
        registry.preferredEndpointId = EndpointId("halo-1")
        registry.unbind(EndpointId("phone-1"))
        assertEquals(EndpointId("halo-1"), registry.preferredEndpointId)
    }

    @Test
    fun resolveNoEndpointReturnsNoEndpoint() = runTest {
        val registry = SenseEndpointRegistry()
        val result = registry.resolve(SenseCapability.AudioInput)
        assertTrue(result is ResolveResult.NoEndpoint)
    }

    @Test
    fun resolveWithTargetReturnsResolved() = runTest {
        val registry = SenseEndpointRegistry()
        registry.bind(FakeEndpoint(makeProfile("halo-1", caps = setOf(SenseCapability.AudioInput))))
        registry.bind(FakeEndpoint(makeProfile("phone-1", kind = BackendKind.PHONE, caps = setOf(SenseCapability.AudioInput))))
        val result = registry.resolve(SenseCapability.AudioInput, target = EndpointId("phone-1"))
        assertTrue(result is ResolveResult.Resolved)
        assertEquals(EndpointId("phone-1"), result.endpoint.profile.endpointId)
    }

    @Test
    fun resolveWithUnknownTargetReturnsNotFound() = runTest {
        val registry = SenseEndpointRegistry()
        val result = registry.resolve(SenseCapability.AudioInput, target = EndpointId("unknown"))
        assertTrue(result is ResolveResult.NotFound)
    }

    @Test
    fun resolveWithUnsupportedCapabilityReturnsUnsupported() = runTest {
        val registry = SenseEndpointRegistry()
        registry.bind(FakeEndpoint(makeProfile("chat-1", kind = BackendKind.CHAT, caps = setOf(SenseCapability.TextInput))))
        val result = registry.resolve(SenseCapability.AudioInput, target = EndpointId("chat-1"))
        assertTrue(result is ResolveResult.Unsupported)
    }

    @Test
    fun unbindAllDisconnectsAll() = runTest {
        val registry = SenseEndpointRegistry()
        val ep1 = FakeEndpoint(makeProfile("halo-1"))
        val ep2 = FakeEndpoint(makeProfile("chat-1", kind = BackendKind.CHAT))
        registry.bind(ep1)
        registry.bind(ep2)
        registry.unbindAll()
        assertEquals(0, registry.endpointProfiles.value.size)
        assertTrue(ep1.disconnected)
        assertTrue(ep2.disconnected)
    }

    @Test
    fun nextOperationIdIsUnique() {
        val registry = SenseEndpointRegistry()
        val ids = (1..100).map { registry.nextOperationId() }.toSet()
        assertEquals(100, ids.size)
    }

    // ------------------------------------------------------------------ operation tracking

    @Test
    fun registerAndCompleteOperation() = runTest {
        val registry = SenseEndpointRegistry()
        val endpoint = FakeEndpoint(makeProfile("halo-1"))
        registry.bind(endpoint)

        val job = launch { delay(10000) }
        registry.registerOperation(EndpointId("halo-1"), "op-1", job)
        assertEquals(1, registry.activeOperationCount(EndpointId("halo-1")))

        registry.completeOperation(EndpointId("halo-1"), "op-1")
        assertEquals(0, registry.activeOperationCount(EndpointId("halo-1")))
        job.cancel()
    }

    @Test
    fun unbindCancelsActiveOperations() = runTest {
        val registry = SenseEndpointRegistry()
        registry.cancellationGracePeriodMillis = 500
        val endpoint = FakeEndpoint(makeProfile("halo-1"))
        registry.bind(endpoint)

        var operationCancelled = false
        val job = launch {
            try { delay(10000) } catch (e: CancellationException) { operationCancelled = true }
        }
        runCurrent()
        registry.registerOperation(EndpointId("halo-1"), "op-1", job)
        assertEquals(1, registry.activeOperationCount(EndpointId("halo-1")))

        registry.unbind(EndpointId("halo-1"))
        runCurrent()
        assertTrue(operationCancelled, "operation should have been cancelled")
        assertTrue(endpoint.disconnected)
    }

    @Test
    fun bindReplacesAndCancelsOldOperations() = runTest {
        val registry = SenseEndpointRegistry()
        registry.cancellationGracePeriodMillis = 500
        val ep1 = FakeEndpoint(makeProfile("halo-1"))
        val ep2 = FakeEndpoint(makeProfile("halo-1"))
        registry.bind(ep1)

        var operationCancelled = false
        val job = launch {
            try { delay(10000) } catch (e: CancellationException) { operationCancelled = true }
        }
        runCurrent()
        registry.registerOperation(EndpointId("halo-1"), "op-1", job)

        registry.bind(ep2)  // replace ep1 with ep2
        runCurrent()
        assertTrue(operationCancelled, "old operation should have been cancelled")
        assertTrue(ep1.disconnected)
        assertEquals(ep2, registry.get(EndpointId("halo-1")))
    }

    @Test
    fun unbindAllCancelsAllOperations() = runTest {
        val registry = SenseEndpointRegistry()
        registry.cancellationGracePeriodMillis = 500
        val ep1 = FakeEndpoint(makeProfile("halo-1"))
        val ep2 = FakeEndpoint(makeProfile("chat-1", kind = BackendKind.CHAT))
        registry.bind(ep1)
        registry.bind(ep2)

        var op1Cancelled = false
        var op2Cancelled = false
        val job1 = launch { try { delay(10000) } catch (e: CancellationException) { op1Cancelled = true } }
        val job2 = launch { try { delay(10000) } catch (e: CancellationException) { op2Cancelled = true } }
        runCurrent()
        registry.registerOperation(EndpointId("halo-1"), "op-1", job1)
        registry.registerOperation(EndpointId("chat-1"), "op-2", job2)

        registry.unbindAll()
        runCurrent()
        assertTrue(op1Cancelled)
        assertTrue(op2Cancelled)
        assertTrue(ep1.disconnected)
        assertTrue(ep2.disconnected)
    }

    @Test
    fun cancelOperationsCancelsOnlyMatchingCapability() = runTest {
        val registry = SenseEndpointRegistry()
        registry.cancellationGracePeriodMillis = 500
        val endpoint = FakeEndpoint(makeProfile("halo-1"))
        registry.bind(endpoint)

        var audioCancelled = false
        var visualCancelled = false
        val audioJob = launch { try { delay(10000) } catch (e: CancellationException) { audioCancelled = true } }
        val visualJob = launch { try { delay(10000) } catch (e: CancellationException) { visualCancelled = true } }
        runCurrent()
        registry.registerOperation(EndpointId("halo-1"), "op-audio", audioJob, SenseCapability.AudioOutput)
        registry.registerOperation(EndpointId("halo-1"), "op-visual", visualJob, SenseCapability.VisualOutput)

        assertTrue(registry.hasActiveOperation(EndpointId("halo-1"), SenseCapability.AudioOutput))
        val cancelled = registry.cancelOperations(EndpointId("halo-1"), SenseCapability.AudioOutput)
        runCurrent()

        assertEquals(1, cancelled)
        assertTrue(audioCancelled, "audio op should have been cancelled")
        assertFalse(visualCancelled, "visual op should still be running")
        assertFalse(registry.hasActiveOperation(EndpointId("halo-1"), SenseCapability.AudioOutput))
        visualJob.cancel()
    }

    @Test
    fun cancelOperationsWithNoMatchIsNoOp() = runTest {
        val registry = SenseEndpointRegistry()
        val endpoint = FakeEndpoint(makeProfile("halo-1"))
        registry.bind(endpoint)

        assertEquals(0, registry.cancelOperations(EndpointId("halo-1"), SenseCapability.AudioOutput))
        assertFalse(registry.hasActiveOperation(EndpointId("halo-1"), SenseCapability.AudioOutput))
    }

    @Test
    fun unbindWithoutOperationsJustDisconnects() = runTest {
        val registry = SenseEndpointRegistry()
        val endpoint = FakeEndpoint(makeProfile("halo-1"))
        registry.bind(endpoint)
        registry.unbind(EndpointId("halo-1"))
        assertTrue(endpoint.disconnected)
    }

    @Test
    fun activeOperationCountReturnsZeroForUnknownEndpoint() {
        val registry = SenseEndpointRegistry()
        assertEquals(0, registry.activeOperationCount(EndpointId("unknown")))
    }
}
