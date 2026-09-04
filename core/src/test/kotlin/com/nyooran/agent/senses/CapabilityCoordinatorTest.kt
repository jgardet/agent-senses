package com.nyooran.agent.senses

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CapabilityCoordinatorTest {

    private fun makeProfile(
        domains: Map<SenseCapability, String> = emptyMap(),
        conflicts: Set<Pair<SenseCapability, SenseCapability>> = emptySet(),
        limits: Map<SenseCapability, SenseLimits> = emptyMap(),
    ) = SenseProfile(
        endpointId = EndpointId("test"),
        backendName = "test",
        backendKind = BackendKind.SIMULATOR,
        state = EndpointState.READY,
        capabilities = domains.keys,
        limits = limits,
        concurrency = ConcurrencyProfile(domains, conflicts),
    )

    private class FakeEndpoint(override val profile: SenseProfile) : SenseEndpoint {
        override suspend fun connect() {}
        override suspend fun disconnect() {}
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
    fun sameDomainOperationsAreSerialized() = runTest {
        val coordinator = CapabilityCoordinator()
        val endpoint = FakeEndpoint(makeProfile(
            domains = mapOf(
                SenseCapability.AudioInput to "audio",
                SenseCapability.AudioOutput to "audio",
            ),
        ))
        val executionOrder = mutableListOf<String>()

        val job1 = async {
            coordinator.withCapability(endpoint, SenseCapability.AudioInput) {
                executionOrder.add("audio-start")
                delay(100)
                executionOrder.add("audio-end")
            }
        }
        val job2 = async {
            delay(50)  // start after job1
            coordinator.withCapability(endpoint, SenseCapability.AudioOutput) {
                executionOrder.add("output-start")
                delay(100)
                executionOrder.add("output-end")
            }
        }
        awaitAll(job1, job2)

        // job1 should complete before job2 starts (same "audio" domain)
        assertEquals(listOf("audio-start", "audio-end", "output-start", "output-end"), executionOrder)
    }

    @Test
    fun differentDomainOperationsOverlap() = runTest {
        val coordinator = CapabilityCoordinator()
        val endpoint = FakeEndpoint(makeProfile(
            domains = mapOf(
                SenseCapability.AudioInput to "audio",
                SenseCapability.VisualOutput to "display",
            ),
        ))
        val executionOrder = mutableListOf<String>()

        val job1 = async {
            coordinator.withCapability(endpoint, SenseCapability.AudioInput) {
                executionOrder.add("audio-start")
                delay(100)
                executionOrder.add("audio-end")
            }
        }
        val job2 = async {
            delay(50)
            coordinator.withCapability(endpoint, SenseCapability.VisualOutput) {
                executionOrder.add("display-start")
                delay(100)
                executionOrder.add("display-end")
            }
        }
        awaitAll(job1, job2)

        // display should start while audio is still running (different domains)
        assertTrue(executionOrder.indexOf("display-start") < executionOrder.indexOf("audio-end"),
            "display should start before audio ends, order was: $executionOrder")
    }

    @Test
    fun explicitConflictPreventsOverlap() = runTest {
        val coordinator = CapabilityCoordinator()
        val endpoint = FakeEndpoint(makeProfile(
            domains = mapOf(
                SenseCapability.AudioInput to "audio",
                SenseCapability.ImageInput to "camera",
            ),
            conflicts = setOf(SenseCapability.AudioInput to SenseCapability.ImageInput),
        ))
        val executionOrder = mutableListOf<String>()

        val job1 = async {
            coordinator.withCapability(endpoint, SenseCapability.AudioInput) {
                executionOrder.add("audio-start")
                delay(100)
                executionOrder.add("audio-end")
            }
        }
        val job2 = async {
            delay(50)
            coordinator.withCapability(endpoint, SenseCapability.ImageInput) {
                executionOrder.add("camera-start")
                delay(100)
                executionOrder.add("camera-end")
            }
        }
        awaitAll(job1, job2)

        // camera should wait for audio to finish (explicit conflict)
        assertEquals(listOf("audio-start", "audio-end", "camera-start", "camera-end"), executionOrder)
    }

    @Test
    fun differentEndpointsDoNotBlockEachOther() = runTest {
        val coordinator = CapabilityCoordinator()
        val ep1 = FakeEndpoint(makeProfile(domains = mapOf(SenseCapability.AudioInput to "audio")))
        val ep2 = FakeEndpoint(SenseProfile(
            endpointId = EndpointId("ep2"),
            backendName = "ep2",
            backendKind = BackendKind.SIMULATOR,
            state = EndpointState.READY,
            capabilities = setOf(SenseCapability.AudioInput),
            limits = emptyMap(),
            concurrency = ConcurrencyProfile(mapOf(SenseCapability.AudioInput to "audio")),
        ))
        val executionOrder = mutableListOf<String>()

        val job1 = async {
            coordinator.withCapability(ep1, SenseCapability.AudioInput) {
                executionOrder.add("ep1-start")
                delay(100)
                executionOrder.add("ep1-end")
            }
        }
        val job2 = async {
            delay(50)
            coordinator.withCapability(ep2, SenseCapability.AudioInput) {
                executionOrder.add("ep2-start")
                delay(100)
                executionOrder.add("ep2-end")
            }
        }
        awaitAll(job1, job2)

        // ep2 should start while ep1 is still running (different endpoints)
        assertTrue(executionOrder.indexOf("ep2-start") < executionOrder.indexOf("ep1-end"),
            "ep2 should start before ep1 ends, order was: $executionOrder")
    }

    @Test
    fun maxConcurrentLimitsParallelism() = runTest {
        val coordinator = CapabilityCoordinator()
        val endpoint = FakeEndpoint(makeProfile(
            domains = mapOf(SenseCapability.VisualOutput to "display"),
            limits = mapOf(SenseCapability.VisualOutput to SenseLimits(maxConcurrent = 1)),
        ))
        val executionOrder = mutableListOf<String>()

        val job1 = async {
            coordinator.withCapability(endpoint, SenseCapability.VisualOutput) {
                executionOrder.add("v1-start")
                delay(100)
                executionOrder.add("v1-end")
            }
        }
        val job2 = async {
            delay(50)
            coordinator.withCapability(endpoint, SenseCapability.VisualOutput) {
                executionOrder.add("v2-start")
                delay(100)
                executionOrder.add("v2-end")
            }
        }
        awaitAll(job1, job2)

        // maxConcurrent=1 → serialized
        assertEquals(listOf("v1-start", "v1-end", "v2-start", "v2-end"), executionOrder)
    }

    @Test
    fun clearEndpointRemovesLocks() {
        val coordinator = CapabilityCoordinator()
        // Just verify it doesn't throw
        coordinator.clearEndpoint(EndpointId("test"))
    }
}
