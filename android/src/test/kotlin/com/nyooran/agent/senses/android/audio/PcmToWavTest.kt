package com.nyooran.agent.senses.android.audio

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PcmToWavTest {

    @Test
    fun producesValidWavFor16bitMono() {
        val pcm = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(1000)
            putShort(-1000)
        }.array()

        val wav = PcmToWav.fromPcm16(pcm, sampleRate = 16000, numChannels = 1, bitsPerSample = 16)
        assertTrue(wav.copyOfRange(0, 4).toString(Charsets.US_ASCII).startsWith("RIFF"))
        assertTrue(wav.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WAVE")
        assertTrue(wav.copyOfRange(12, 16).toString(Charsets.US_ASCII) == "fmt ")

        val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, buffer.getShort(20).toInt() and 0xffff) // PCM
        assertEquals(1, buffer.getShort(22).toInt() and 0xffff) // channels
        assertEquals(16000, buffer.getInt(24)) // sample rate
        assertEquals(16, buffer.getShort(34).toInt() and 0xffff) // bits per sample
        assertEquals(4, buffer.getInt(40)) // data chunk size
        assertContentEquals(pcm, wav.copyOfRange(44, 48))
    }

    @Test
    fun roundTripWithWavReader() = runTest {
        val pcm = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(1000)
            putShort(-1000)
            putShort(5000)
            putShort(-5000)
        }.array()

        val wav = PcmToWav.fromPcm16(pcm, sampleRate = 16000)
        val result = WavReader.readPcm(ByteArrayInputStream(wav), targetSampleRate = 16000).toList()
            .fold(byteArrayOf()) { acc, chunk -> acc + chunk }
        assertContentEquals(pcm, result)
    }
}
