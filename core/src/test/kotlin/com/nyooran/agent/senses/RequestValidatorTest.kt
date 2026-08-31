package com.nyooran.agent.senses

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RequestValidatorTest {

    private fun profile(
        caps: Set<SenseCapability> = setOf(SenseCapability.AudioInput, SenseCapability.ImageInput, SenseCapability.AudioOutput, SenseCapability.VisualOutput, SenseCapability.TextOutput),
        limits: Map<SenseCapability, SenseLimits> = emptyMap(),
    ) = SenseProfile(
        endpointId = EndpointId("test"),
        backendName = "test",
        backendKind = BackendKind.SIMULATOR,
        state = EndpointState.READY,
        capabilities = caps,
        limits = limits,
        concurrency = ConcurrencyProfile(),
    )

    @Test
    fun unsupportedCapabilityReturnsUnavailable() {
        val p = profile(caps = setOf(SenseCapability.TextInput))
        val failure = RequestValidator.validate(
            SenseCapability.AudioInput,
            AudioInputRequest(maxDurationMillis = 100, maxBytes = 1024),
            p,
        )
        assertNotNull(failure)
        assertEquals(FailureCategory.UNAVAILABLE, failure.category)
    }

    @Test
    fun validRequestReturnsNull() {
        val p = profile()
        val failure = RequestValidator.validate(
            SenseCapability.AudioInput,
            AudioInputRequest(maxDurationMillis = 1000, maxBytes = 1024),
            p,
        )
        assertNull(failure)
    }

    @Test
    fun zeroTimeoutReturnsRejected() {
        val p = profile()
        val failure = RequestValidator.validate(
            SenseCapability.AudioInput,
            AudioInputRequest(maxDurationMillis = 100, maxBytes = 1024, timeoutMillis = 0),
            p,
        )
        assertNotNull(failure)
        assertEquals(FailureCategory.REJECTED, failure.category)
        assertTrue(failure.message.contains("timeoutMillis"))
    }

    @Test
    fun durationExceedsLimitReturnsLimitExceeded() {
        val p = profile(limits = mapOf(
            SenseCapability.AudioInput to SenseLimits(maxDurationMillis = 5000)
        ))
        val failure = RequestValidator.validate(
            SenseCapability.AudioInput,
            AudioInputRequest(maxDurationMillis = 10000, maxBytes = 1024),
            p,
        )
        assertNotNull(failure)
        assertEquals(FailureCategory.LIMIT_EXCEEDED, failure.category)
    }

    @Test
    fun zeroMaxBytesReturnsRejected() {
        val p = profile()
        val failure = RequestValidator.validate(
            SenseCapability.AudioInput,
            AudioInputRequest(maxDurationMillis = 100, maxBytes = 0),
            p,
        )
        assertNotNull(failure)
        assertEquals(FailureCategory.REJECTED, failure.category)
    }

    @Test
    fun emptyAudioOutputReturnsRejected() {
        val p = profile()
        val failure = RequestValidator.validate(
            SenseCapability.AudioOutput,
            AudioOutputRequest(audio = ByteArray(0), format = AudioFormat(16000, 16, 1, "wav", "audio/wav")),
            p,
        )
        assertNotNull(failure)
        assertEquals(FailureCategory.REJECTED, failure.category)
    }

    @Test
    fun badSampleRateReturnsRejected() {
        val p = profile()
        val failure = RequestValidator.validate(
            SenseCapability.AudioOutput,
            AudioOutputRequest(audio = ByteArray(10), format = AudioFormat(0, 16, 1, "wav", "audio/wav")),
            p,
        )
        assertNotNull(failure)
        assertEquals(FailureCategory.REJECTED, failure.category)
    }

    @Test
    fun emptyTextOutputReturnsRejected() {
        val p = profile()
        val failure = RequestValidator.validate(
            SenseCapability.TextOutput,
            TextOutputRequest(content = TextContent("")),
            p,
        )
        assertNotNull(failure)
        assertEquals(FailureCategory.REJECTED, failure.category)
    }

    @Test
    fun emptyVisualPayloadReturnsRejected() {
        val p = profile()
        val failure = RequestValidator.validate(
            SenseCapability.VisualOutput,
            VisualOutputRequest(content = VisualContent(VisualContent.VisualKind.IMAGE, ByteArray(0))),
            p,
        )
        assertNotNull(failure)
        assertEquals(FailureCategory.REJECTED, failure.category)
    }

    @Test
    fun emptyTextVisualPayloadAllowed() {
        val p = profile()
        // TEXT kind with empty payload is allowed (text is in the request, not payload)
        val failure = RequestValidator.validate(
            SenseCapability.VisualOutput,
            VisualOutputRequest(content = VisualContent(VisualContent.VisualKind.TEXT, ByteArray(0))),
            p,
        )
        assertNull(failure)
    }

    @Test
    fun imageBytesExceedLimitReturnsLimitExceeded() {
        val p = profile(limits = mapOf(
            SenseCapability.ImageInput to SenseLimits(maxBytes = 100)
        ))
        val failure = RequestValidator.validate(
            SenseCapability.ImageInput,
            ImageInputRequest(resolution = 640, qualityIndex = 0, maxBytes = 1000),
            p,
        )
        assertNotNull(failure)
        assertEquals(FailureCategory.LIMIT_EXCEEDED, failure.category)
    }

    @Test
    fun zeroResolutionReturnsRejected() {
        val p = profile()
        val failure = RequestValidator.validate(
            SenseCapability.ImageInput,
            ImageInputRequest(resolution = 0, qualityIndex = 0, maxBytes = 1024),
            p,
        )
        assertNotNull(failure)
        assertEquals(FailureCategory.REJECTED, failure.category)
    }

    // ------------------------------------------------------------------ MultiModalResult

    @Test
    fun multiModalAllSucceeded() {
        val result = MultiModalResult(listOf(
            ModalityResult.Success(SenseCapability.AudioOutput, AudioOutputResult(
                Provenance("op-1", EndpointId("test"), "test", BackendKind.SIMULATOR,
                    SenseCapability.AudioOutput, ResultOrigin.SPEAKER, startedAt = 0, completedAt = 1)
            )),
            ModalityResult.Success(SenseCapability.VisualOutput, VisualOutputResult(
                Provenance("op-2", EndpointId("test"), "test", BackendKind.SIMULATOR,
                    SenseCapability.VisualOutput, ResultOrigin.DISPLAY, startedAt = 0, completedAt = 1)
            )),
        ))
        assertTrue(result.allSucceeded)
        assertTrue(result.anySucceeded)
        assertTrue(!result.anyFailed)
        assertEquals(DeliveryStatus.DELIVERED, result.deliveryStatus)
    }

    @Test
    fun multiModalPartialSuccess() {
        val result = MultiModalResult(listOf(
            ModalityResult.Success(SenseCapability.AudioOutput, AudioOutputResult(
                Provenance("op-1", EndpointId("test"), "test", BackendKind.SIMULATOR,
                    SenseCapability.AudioOutput, ResultOrigin.SPEAKER, startedAt = 0, completedAt = 1)
            )),
            ModalityResult.Failure(SenseCapability.VisualOutput,
                SenseFailure(FailureCategory.DISCONNECTED, "display disconnected")),
        ))
        assertTrue(!result.allSucceeded)
        assertTrue(result.anySucceeded)
        assertTrue(result.anyFailed)
        assertEquals(DeliveryStatus.PARTIALLY_DELIVERED, result.deliveryStatus)
    }

    @Test
    fun multiModalAllFailed() {
        val result = MultiModalResult(listOf(
            ModalityResult.Failure(SenseCapability.AudioOutput,
                SenseFailure(FailureCategory.TIMEOUT, "audio timeout")),
            ModalityResult.Failure(SenseCapability.VisualOutput,
                SenseFailure(FailureCategory.DISCONNECTED, "display disconnected")),
        ))
        assertTrue(!result.allSucceeded)
        assertTrue(!result.anySucceeded)
        assertTrue(result.anyFailed)
        assertEquals(DeliveryStatus.FAILED, result.deliveryStatus)
    }
}
