package com.nyooran.agent.senses.simulator

import com.nyooran.agent.senses.*
import com.nyooran.agent.senses.orchestration.SemanticSenseWorkflows
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SemanticSenseWorkflowsTest {

    // ------------------------------------------------------------------ mock models

    private class MockTranscriptionModel(
        private val transcript: String = "hello world",
    ) : TranscriptionModel {
        override suspend fun transcribe(audio: ByteArray, format: AudioFormat) =
            TranscriptionResult(transcript = transcript, confidence = 0.95f)
    }

    private class MockVisionModel(
        private val description: String = "a person walking",
        private val objects: List<String> = listOf("person", "sidewalk"),
    ) : VisionModel {
        override suspend fun observe(image: ByteArray, format: ImageFormat, prompt: String?) =
            VisionResult(description = description, confidence = 0.88f, objects = objects)
    }

    private class MockTtsModel : TtsModel {
        override suspend fun synthesize(text: String, format: AudioFormat) =
            TtsResult(
                audio = ByteArray(100) { 0x55 },
                format = format,
                durationMillis = 500,
            )
    }

    private class FailingTranscriptionModel : TranscriptionModel {
        override suspend fun transcribe(audio: ByteArray, format: AudioFormat) =
            throw SensesError.Internal("transcription failed")
    }

    private class FailingVisionModel : VisionModel {
        override suspend fun observe(image: ByteArray, format: ImageFormat, prompt: String?) =
            throw SensesError.Internal("vision failed")
    }

    private suspend fun workflows(
        transcription: TranscriptionModel = MockTranscriptionModel(),
        vision: VisionModel = MockVisionModel(),
        tts: TtsModel = MockTtsModel(),
    ): SemanticSenseWorkflows {
        val registry = SenseEndpointRegistry()
        registry.bind(SimulatedHaloEndpoint())
        return SemanticSenseWorkflows(registry, transcription, vision, tts)
    }

    // ------------------------------------------------------------------ listenAndTranscribe

    @Test
    fun listenAndTranscribeReturnsTranscript() = runTest {
        val wf = workflows()
        val result = wf.listenAndTranscribe().getOrThrow()
        assertEquals("hello world", result.transcript)
        assertEquals(0.95f, result.confidence)
    }

    @Test
    fun listenAndTranscribePreservesBothProvenance() = runTest {
        val wf = workflows()
        val result = wf.listenAndTranscribe().getOrThrow()
        assertEquals(ResultOrigin.MICROPHONE, result.rawAudioProvenance.origin)
        assertEquals(ResultOrigin.MODEL, result.transcriptionProvenance.origin)
        assertEquals(BackendKind.SIMULATOR, result.rawAudioProvenance.backendKind)
        assertEquals(BackendKind.MODEL, result.transcriptionProvenance.backendKind)
        assertTrue(result.transcriptionProvenance.transformations.contains(Transformation.ASR_TRANSCRIPTION))
    }

    @Test
    fun listenAndTranscribeKeepsRawAudioWhenRequested() = runTest {
        val wf = workflows()
        val result = wf.listenAndTranscribe(keepRaw = true).getOrThrow()
        assertNotNull(result.rawAudio)
        assertTrue(result.rawAudio!!.isNotEmpty())
    }

    @Test
    fun listenAndTranscribeOmitsRawAudioByDefault() = runTest {
        val wf = workflows()
        val result = wf.listenAndTranscribe().getOrThrow()
        assertNull(result.rawAudio)
    }

    @Test
    fun listenAndTranscribeFailsWhenModelFails() = runTest {
        val wf = workflows(transcription = FailingTranscriptionModel())
        val result = wf.listenAndTranscribe()
        assertTrue(result.isFailure)
    }

    // ------------------------------------------------------------------ lookAndObserve

    @Test
    fun lookAndObserveReturnsDescription() = runTest {
        val wf = workflows()
        val result = wf.lookAndObserve().getOrThrow()
        assertEquals("a person walking", result.description)
        assertEquals(listOf("person", "sidewalk"), result.objects)
    }

    @Test
    fun lookAndObservePreservesBothProvenance() = runTest {
        val wf = workflows()
        val result = wf.lookAndObserve().getOrThrow()
        assertEquals(ResultOrigin.CAMERA, result.rawImageProvenance.origin)
        assertEquals(ResultOrigin.MODEL, result.observationProvenance.origin)
        assertTrue(result.observationProvenance.transformations.contains(Transformation.VISION_ANALYSIS))
    }

    @Test
    fun lookAndObserveOmitsRawImageByDefault() = runTest {
        val wf = workflows()
        val result = wf.lookAndObserve().getOrThrow()
        assertNull(result.rawImage)
    }

    @Test
    fun lookAndObserveFailsWhenModelFails() = runTest {
        val wf = workflows(vision = FailingVisionModel())
        val result = wf.lookAndObserve()
        assertTrue(result.isFailure)
    }

    // ------------------------------------------------------------------ speakText

    @Test
    fun speakTextReturnsBothProvenance() = runTest {
        val wf = workflows()
        val result = wf.speakText("hello").getOrThrow()
        assertEquals(BackendKind.MODEL, result.ttsProvenance.backendKind)
        assertEquals(BackendKind.SIMULATOR, result.outputProvenance.backendKind)
        assertTrue(result.ttsProvenance.transformations.contains(Transformation.TTS_SYNTHESIS))
    }

    // ------------------------------------------------------------------ presentSemantic

    @Test
    fun presentSemanticReturnsProvenance() = runTest {
        val wf = workflows()
        val result = wf.presentSemantic(
            VisualContent(VisualContent.VisualKind.TEXT, "hello".toByteArray()),
        ).getOrThrow()
        assertEquals(BackendKind.SIMULATOR, result.outputProvenance.backendKind)
    }

    // ------------------------------------------------------------------ multiModalTurn

    @Test
    fun multiModalTurnReturnsBothResults() = runTest {
        val wf = workflows()
        val result = wf.multiModalTurn()
        assertTrue(result.isComplete)
        assertNotNull(result.listen)
        assertNotNull(result.look)
        assertEquals("hello world", result.listen!!.transcript)
        assertEquals("a person walking", result.look!!.description)
    }

    @Test
    fun multiModalTurnReturnsPartialWhenVisionFails() = runTest {
        val wf = workflows(vision = FailingVisionModel())
        val result = wf.multiModalTurn()
        assertTrue(result.isPartial)
        assertNotNull(result.listen)
        assertNotNull(result.lookFailure)
        assertNull(result.look)
    }

    @Test
    fun multiModalTurnReturnsPartialWhenTranscriptionFails() = runTest {
        val wf = workflows(transcription = FailingTranscriptionModel())
        val result = wf.multiModalTurn()
        assertTrue(result.isPartial)
        assertNotNull(result.look)
        assertNotNull(result.listenFailure)
        assertNull(result.listen)
    }

    @Test
    fun multiModalTurnReturnsFailedWhenBothFail() = runTest {
        val wf = workflows(
            transcription = FailingTranscriptionModel(),
            vision = FailingVisionModel(),
        )
        val result = wf.multiModalTurn()
        assertTrue(result.isFailed)
        assertNull(result.listen)
        assertNull(result.look)
    }
}
