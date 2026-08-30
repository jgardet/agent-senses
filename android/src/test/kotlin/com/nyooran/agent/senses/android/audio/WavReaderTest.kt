package com.nyooran.agent.senses.android.audio

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WavReaderTest {

    private suspend fun readAll(wav: ByteArray, targetSampleRate: Int = 16000): ByteArray {
        return WavReader.readPcm(ByteArrayInputStream(wav), targetSampleRate).toList()
            .fold(byteArrayOf()) { acc, chunk -> acc + chunk }
    }

    @Test
    fun rejectsShortInput() = runTest {
        assertFailsWith<IllegalArgumentException> {
            WavReader.readPcm(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 16000).toList()
        }
    }

    @Test
    fun rejectsNonRiff() = runTest {
        val data = ByteArray(100) { it.toByte() }
        assertFailsWith<IllegalArgumentException> {
            WavReader.readPcm(ByteArrayInputStream(data), 16000).toList()
        }
    }

    @Test
    fun readsMono16bitAtTargetRate() = runTest {
        val pcm = shortArrayOf(1000, -1000, 5000, -5000)
        val wav = buildWav(pcm, sampleRate = 16000, channels = 1, bits = 16)
        val result = readAll(wav)
        val expected = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            pcm.forEach { putShort(it) }
        }.array()
        assertContentEquals(expected, result)
    }

    @Test
    fun convertsStereoToMonoAndResamples() = runTest {
        val left = shortArrayOf(1000, 2000, 3000)
        val right = shortArrayOf(3000, 2000, 1000)
        val interleaved = ShortArray(left.size * 2)
        for (i in left.indices) {
            interleaved[i * 2] = left[i]
            interleaved[i * 2 + 1] = right[i]
        }
        val wav = buildWav(interleaved, sampleRate = 24000, channels = 2, bits = 16)
        val result = readAll(wav, targetSampleRate = 16000)
        // Result is mono 16 kHz PCM; assert it has 2 samples per channel (3 * 16000 / 24000 = 2)
        assertEquals(2 * 2, result.size)
    }

    @Test
    fun converts8bitTo16bit() = runTest {
        val unsigned = byteArrayOf(0, 128.toByte(), 255.toByte())
        val wav = buildWav8(unsigned, sampleRate = 16000, channels = 1)
        val result = readAll(wav)
        assertEquals(unsigned.size * 2, result.size)
    }

    @Test
    fun streamsMono16bitFromInputStream() = runTest {
        val pcm = shortArrayOf(1000, -1000, 5000, -5000)
        val wav = buildWav(pcm, sampleRate = 16000, channels = 1, bits = 16)
        val chunks = WavReader.readPcm(ByteArrayInputStream(wav), targetSampleRate = 16000).toList()
        val result = chunks.fold(byteArrayOf()) { acc, chunk -> acc + chunk }
        val expected = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            pcm.forEach { putShort(it) }
        }.array()
        assertContentEquals(expected, result)
    }

    private fun buildWav(samples: ShortArray, sampleRate: Int, channels: Int, bits: Int): ByteArray {
        val bytesPerSample = bits / 8
        val dataSize = samples.size * 2
        val wav = ByteArray(44 + dataSize)
        val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)

        "RIFF".forEach { buffer.put(it.code.toByte()) }
        buffer.putInt(36 + dataSize)
        "WAVE".forEach { buffer.put(it.code.toByte()) }
        "fmt ".forEach { buffer.put(it.code.toByte()) }
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * channels * bytesPerSample)
        buffer.putShort((channels * bytesPerSample).toShort())
        buffer.putShort(bits.toShort())
        "data".forEach { buffer.put(it.code.toByte()) }
        buffer.putInt(dataSize)
        samples.forEach { buffer.putShort(it) }
        return wav
    }

    private fun buildWav8(unsigned: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val dataSize = unsigned.size
        val wav = ByteArray(44 + dataSize)
        val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)

        "RIFF".forEach { buffer.put(it.code.toByte()) }
        buffer.putInt(36 + dataSize)
        "WAVE".forEach { buffer.put(it.code.toByte()) }
        "fmt ".forEach { buffer.put(it.code.toByte()) }
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * channels)
        buffer.putShort(channels.toShort())
        buffer.putShort(8)
        "data".forEach { buffer.put(it.code.toByte()) }
        buffer.putInt(dataSize)
        unsigned.forEach { buffer.put(it) }
        return wav
    }
}
