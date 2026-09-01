package com.nyooran.agent.senses.audio

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Streaming WAV reader for PCM data produced by Android [TextToSpeech] or
 * other TTS sources. Resides in `core` so both JVM backend modules and
 * Android clients can share one implementation.
 *
 * Only supports uncompressed PCM (audio format 1), 8 or 16 bits per sample,
 * mono or stereo. Emits 16-bit little-endian mono PCM at [targetSampleRate]
 * in [frameMillis]-length chunks by linear resampling and channel mixing.
 *
 * The flow-based API keeps at most one output frame in memory at a time, so
 * long TTS utterances can be sent directly to a speaker or transcoder without
 * loading the whole resampled PCM into a single [ByteArray].
 */
object WavReader {

    /**
     * Read and resample WAV data from [input], emitting PCM chunks.
     *
     * @param input WAV input stream. Closed by this function.
     * @param targetSampleRate output sample rate (default 16 kHz).
     * @param frameMillis desired chunk duration in milliseconds; the actual chunk
     * size is rounded to whole samples and is at most one frame's worth.
     */
    fun readPcm(
        input: InputStream,
        targetSampleRate: Int = 16000,
        frameMillis: Int = 10,
    ): Flow<ByteArray> = flow {
        input.use { stream ->
            val header = ByteArray(12)
            readFully(stream, header)

            require(header.copyOfRange(0, 4).contentEquals("RIFF".toByteArray())) { "Not a RIFF file" }
            require(header.copyOfRange(8, 12).contentEquals("WAVE".toByteArray())) { "Not a WAVE file" }

            var format = -1
            var channels = -1
            var sampleRate = -1
            var bitsPerSample = -1
            var dataSize = -1

            val chunkHeader = ByteArray(8)
            while (true) {
                if (stream.read(chunkHeader) != 8) break
                val chunkId = chunkHeader.copyOfRange(0, 4).toString(Charsets.US_ASCII)
                val chunkSize = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                when (chunkId) {
                    "fmt " -> {
                        val fmt = ByteArray(chunkSize)
                        readFully(stream, fmt)
                        val buffer = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                        format = buffer.getShort(0).toInt() and 0xffff
                        channels = buffer.getShort(2).toInt() and 0xffff
                        sampleRate = buffer.getInt(4)
                        bitsPerSample = buffer.getShort(14).toInt() and 0xffff
                    }
                    "data" -> {
                        dataSize = chunkSize
                        break
                    }
                    else -> skipFully(stream, chunkSize + (chunkSize % 2))
                }
            }

            require(format != -1) { "WAV fmt chunk not found" }
            require(dataSize != -1) { "WAV data chunk not found" }
            require(format == 1) { "Only PCM WAV is supported, got format $format" }
            require(channels == 1 || channels == 2) { "Only mono or stereo WAV supported" }
            require(bitsPerSample == 8 || bitsPerSample == 16) { "Only 8 or 16-bit WAV supported" }

            val bytesPerSample = bitsPerSample / 8
            val bytesPerFrame = channels * bytesPerSample
            val frameSize = (targetSampleRate * frameMillis / 1000).coerceAtLeast(1) * 2
            val pcmBuffer = ByteBuffer.allocate(frameSize).order(ByteOrder.LITTLE_ENDIAN)

            suspend fun emitChunk() {
                if (pcmBuffer.position() > 0) {
                    val chunk = pcmBuffer.array().copyOf(pcmBuffer.position())
                    emit(chunk)
                    pcmBuffer.clear()
                }
            }

            suspend fun writeShort(value: Short) {
                pcmBuffer.putShort(value)
                if (!pcmBuffer.hasRemaining()) {
                    emitChunk()
                }
            }

            val resampler = PcmResampler(inputRate = sampleRate, outputRate = targetSampleRate)
            var remaining = dataSize
            val readBuffer = ByteArray(bytesPerFrame)

            while (remaining >= bytesPerFrame) {
                readFully(stream, readBuffer)
                remaining -= bytesPerFrame
                val sample = when {
                    bitsPerSample == 8 && channels == 1 ->
                        ((readBuffer[0].toInt() and 0xff) - 128).toShort()
                    bitsPerSample == 8 && channels == 2 -> {
                        val left = (readBuffer[0].toInt() and 0xff) - 128
                        val right = (readBuffer[1].toInt() and 0xff) - 128
                        ((left + right) / 2).toShort()
                    }
                    bitsPerSample == 16 && channels == 1 ->
                        ByteBuffer.wrap(readBuffer, 0, 2).order(ByteOrder.LITTLE_ENDIAN).short
                    bitsPerSample == 16 && channels == 2 -> {
                        val left = ByteBuffer.wrap(readBuffer, 0, 2).order(ByteOrder.LITTLE_ENDIAN).short
                        val right = ByteBuffer.wrap(readBuffer, 2, 2).order(ByteOrder.LITTLE_ENDIAN).short
                        ((left.toInt() + right.toInt()) / 2).toShort()
                    }
                    else -> throw IllegalArgumentException("Unsupported WAV format")
                }
                resampler.push(sample) { writeShort(it) }
            }

            resampler.flush { writeShort(it) }
            emitChunk()
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            require(read > 0) { "Unexpected end of WAV stream" }
            offset += read
        }
    }

    private fun skipFully(input: InputStream, bytes: Int) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skip(remaining.toLong())
            require(skipped > 0) { "Unexpected end of WAV stream while skipping chunk" }
            remaining -= skipped.toInt()
        }
    }

    private class PcmResampler(inputRate: Int, outputRate: Int) {
        private val ratio = inputRate.toDouble() / outputRate.toDouble()
        private val window = ArrayDeque<Pair<Int, Short>>()
        private var nextInputIndex = 0
        private var outputIndex = 0

        suspend fun push(sample: Short, emit: suspend (Short) -> Unit) {
            window.addLast(nextInputIndex to sample)
            nextInputIndex++
            produce(emit)
        }

        suspend fun flush(emit: suspend (Short) -> Unit) {
            while (true) {
                val src = outputIndex * ratio
                val index = src.toInt()
                val a = window.find { it.first == index }
                val b = window.find { it.first == index + 1 }
                if (a == null && b == null) break
                val aValue = a?.second ?: b!!.second
                val bValue = b?.second ?: aValue
                val frac = src - index
                val value = aValue + (bValue - aValue) * frac
                emit(value.toInt().toShort())
                outputIndex++
                while (window.isNotEmpty() && window.first().first < index) window.removeFirst()
            }
        }

        private suspend fun produce(emit: suspend (Short) -> Unit) {
            while (true) {
                val src = outputIndex * ratio
                val index = src.toInt()
                val a = window.find { it.first == index } ?: break
                val b = window.find { it.first == index + 1 } ?: break
                val frac = src - index
                val value = a.second + (b.second - a.second) * frac
                emit(value.toInt().toShort())
                outputIndex++
                while (window.first().first < index) window.removeFirst()
            }
        }
    }
}
