package com.nyooran.agent.senses.audio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wraps raw 16-bit mono PCM samples in a WAV/RIFF header.
 *
 * Gemma 4's audio preprocessor accepts WAV/FLAC/MP3 and resamples internally;
 * the safest container is a 16 kHz, mono, 16-bit PCM WAV.
 */
object PcmToWav {

    fun fromPcm16(
        pcmData: ByteArray,
        sampleRate: Int = 16000,
        numChannels: Int = 1,
        bitsPerSample: Int = 16,
    ): ByteArray {
        require(bitsPerSample == 8 || bitsPerSample == 16) { "Only 8 or 16-bit PCM supported" }
        require(numChannels == 1 || numChannels == 2) { "Only mono or stereo supported" }

        val byteRate = sampleRate * numChannels * bitsPerSample / 8
        val blockAlign = numChannels * bitsPerSample / 8
        val dataSize = pcmData.size
        val fileSize = 36 + dataSize

        val out = ByteArrayOutputStream(44 + dataSize)

        fun writeIntLE(value: Int, bytes: Int) {
            val buf = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN)
            when (bytes) {
                1 -> buf.put(value.toByte())
                2 -> buf.putShort(value.toShort())
                4 -> buf.putInt(value)
            }
            out.write(buf.array())
        }

        // RIFF chunk
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeIntLE(fileSize, 4)
        out.write("WAVE".toByteArray(Charsets.US_ASCII))

        // fmt chunk
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        writeIntLE(16, 4) // Subchunk1Size (PCM)
        writeIntLE(1, 2)  // AudioFormat (PCM)
        writeIntLE(numChannels, 2)
        writeIntLE(sampleRate, 4)
        writeIntLE(byteRate, 4)
        writeIntLE(blockAlign, 2)
        writeIntLE(bitsPerSample, 2)

        // data chunk
        out.write("data".toByteArray(Charsets.US_ASCII))
        writeIntLE(dataSize, 4)
        out.write(pcmData)

        return out.toByteArray()
    }
}
