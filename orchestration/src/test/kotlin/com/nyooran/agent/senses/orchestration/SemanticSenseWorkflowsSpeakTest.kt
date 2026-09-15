package com.nyooran.agent.senses.orchestration

import com.nyooran.agent.senses.AudioFormat
import com.nyooran.agent.senses.SenseEndpointRegistry
import com.nyooran.agent.senses.TtsModel
import com.nyooran.agent.senses.TtsResult
import com.nyooran.agent.senses.audio.PcmToWav
import com.nyooran.agent.senses.simulator.SimulatedHaloEndpoint
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SemanticSenseWorkflowsSpeakTest {

    private class FakeTts : TtsModel {
        val calls = mutableListOf<String>()
        override suspend fun synthesize(text: String, format: AudioFormat): TtsResult {
            calls += text
            val pcm = ByteArray(160) { 0x10 }
            return TtsResult(
                audio = PcmToWav.fromPcm16(pcm, format.sampleRate, format.channels, format.bitDepth),
                format = format,
                durationMillis = 10,
            )
        }
    }

    @Test
    fun speakTextPipelinesSentencesIntoSeparatePlayback() = runTest {
        val registry = SenseEndpointRegistry()
        val endpoint = SimulatedHaloEndpoint()
        registry.bind(endpoint)
        val tts = FakeTts()
        val workflows = SemanticSenseWorkflows(registry = registry, ttsModel = tts)

        val result = workflows.speakText(
            "First sentence with some length. Second sentence also long enough.",
        ).getOrThrow()

        assertEquals(2, tts.calls.size)
        assertEquals(2, endpoint.recordedAudio.size)
        val merged = result.audio!!
        // Merged diagnostics audio is a valid RIFF/WAV covering both chunks.
        assertEquals("RIFF", String(merged.copyOfRange(0, 4)))
        assertTrue(merged.size > 44 + 160)
    }

    @Test
    fun speakTextShortTextSingleCall() = runTest {
        val registry = SenseEndpointRegistry()
        val endpoint = SimulatedHaloEndpoint()
        registry.bind(endpoint)
        val tts = FakeTts()
        val workflows = SemanticSenseWorkflows(registry = registry, ttsModel = tts)

        val result = workflows.speakText("Short reply.").getOrThrow()

        assertEquals(1, tts.calls.size)
        assertEquals(1, endpoint.recordedAudio.size)
        assertTrue(result.audio!!.isNotEmpty())
    }
}
