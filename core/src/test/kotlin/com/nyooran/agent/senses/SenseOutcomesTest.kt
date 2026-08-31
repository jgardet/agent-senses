package com.nyooran.agent.senses

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SenseOutcomesTest {

    @Test
    fun successWrapsResult() {
        val result = AudioInputResult(
            audio = ByteArray(10),
            format = AudioFormat(16000, 16, 1, "wav", "audio/wav"),
            durationMillis = 1000,
            provenance = Provenance(
                operationId = "op-1",
                endpointId = EndpointId("halo-1"),
                backendName = "Halo",
                backendKind = BackendKind.PHYSICAL,
                capability = SenseCapability.AudioInput,
                origin = ResultOrigin.MICROPHONE,
                startedAt = 1000,
                completedAt = 2000,
            ),
        )
        val outcome = SenseOutcomes.success(result)
        assertTrue(outcome is SenseOutcome.Success)
        assertEquals(result, outcome.result)
    }

    @Test
    fun unavailableFactoryCreatesCorrectCategory() {
        val outcome = SenseOutcomes.unavailable("no mic", endpointId = EndpointId("chat-1"), capability = SenseCapability.AudioInput)
        assertTrue(outcome is SenseOutcome.Failure)
        assertEquals(FailureCategory.UNAVAILABLE, outcome.error.category)
        assertEquals("no mic", outcome.error.message)
        assertEquals(EndpointId("chat-1"), outcome.error.endpointId)
        assertEquals(SenseCapability.AudioInput, outcome.error.capability)
    }

    @Test
    fun allFailureCategoriesAreConstructable() {
        val cases = listOf(
            SenseOutcomes.unavailable("x") to FailureCategory.UNAVAILABLE,
            SenseOutcomes.disconnected("x") to FailureCategory.DISCONNECTED,
            SenseOutcomes.timeout("x") to FailureCategory.TIMEOUT,
            SenseOutcomes.cancelled("x") to FailureCategory.CANCELLED,
            SenseOutcomes.rejected("x") to FailureCategory.REJECTED,
            SenseOutcomes.limitExceeded("x") to FailureCategory.LIMIT_EXCEEDED,
            SenseOutcomes.protocol("x") to FailureCategory.PROTOCOL,
            SenseOutcomes.permissionDenied("x") to FailureCategory.PERMISSION_DENIED,
            SenseOutcomes.modelUnavailable("x") to FailureCategory.MODEL_UNAVAILABLE,
            SenseOutcomes.internal("x") to FailureCategory.INTERNAL,
        )
        for ((outcome, expected) in cases) {
            assertTrue(outcome is SenseOutcome.Failure, "$expected should be a Failure")
            assertEquals(expected, outcome.error.category)
        }
    }

    @Test
    fun provenanceRecordsFullChain() {
        val provenance = Provenance(
            operationId = "op-42",
            endpointId = EndpointId("halo-1"),
            backendName = "Halo",
            backendKind = BackendKind.PHYSICAL,
            capability = SenseCapability.AudioInput,
            origin = ResultOrigin.MICROPHONE,
            fixtureId = null,
            mediaFormat = MediaFormat("wav", "audio/wav", durationMillis = 5000, sampleRate = 16000, channels = 1),
            transformations = listOf(Transformation.NORMALIZATION, Transformation.ASR_TRANSCRIPTION),
            startedAt = 1000,
            completedAt = 3000,
            deliveryStatus = DeliveryStatus.DELIVERED,
        )
        assertEquals("op-42", provenance.operationId)
        assertEquals(BackendKind.PHYSICAL, provenance.backendKind)
        assertEquals(ResultOrigin.MICROPHONE, provenance.origin)
        assertEquals(2, provenance.transformations.size)
        assertNull(provenance.fixtureId)
    }

    @Test
    fun fixtureProvenanceIncludesFixtureId() {
        val provenance = Provenance(
            operationId = "op-99",
            endpointId = EndpointId("sim-1"),
            backendName = "Simulator",
            backendKind = BackendKind.SIMULATOR,
            capability = SenseCapability.StatusInput,
            origin = ResultOrigin.FIXTURE,
            fixtureId = "battery-75",
            startedAt = 0,
            completedAt = 1,
        )
        assertEquals(BackendKind.SIMULATOR, provenance.backendKind)
        assertEquals("battery-75", provenance.fixtureId)
    }

    @Test
    fun sensesErrorBridgesToSenseFailure() {
        val error = SensesError.Unavailable("no camera")
        val failure = error.toSenseFailure(endpointId = EndpointId("chat-1"), capability = SenseCapability.ImageInput)
        assertEquals(FailureCategory.UNAVAILABLE, failure.category)
        assertEquals("no camera", failure.message)
        assertEquals(EndpointId("chat-1"), failure.endpointId)
        assertEquals(SenseCapability.ImageInput, failure.capability)
    }

    @Test
    fun allSensesErrorCategoriesBridge() {
        val cases = listOf(
            SensesError.Unavailable("x") to FailureCategory.UNAVAILABLE,
            SensesError.Disconnected("x") to FailureCategory.DISCONNECTED,
            SensesError.Timeout("x") to FailureCategory.TIMEOUT,
            SensesError.Cancelled("x") to FailureCategory.CANCELLED,
            SensesError.Rejected("x") to FailureCategory.REJECTED,
            SensesError.LimitExceeded("x") to FailureCategory.LIMIT_EXCEEDED,
            SensesError.Protocol("x") to FailureCategory.PROTOCOL,
            SensesError.PermissionDenied("x") to FailureCategory.PERMISSION_DENIED,
            SensesError.ModelUnavailable("x") to FailureCategory.MODEL_UNAVAILABLE,
            SensesError.Internal("x") to FailureCategory.INTERNAL,
        )
        for ((error, expected) in cases) {
            assertEquals(expected, error.toSenseFailure().category)
        }
    }
}
