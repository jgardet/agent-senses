package com.nyooran.agent.senses.simulator

import com.nyooran.agent.senses.audio.PcmToWav
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Deterministic, valid media fixtures for the simulator.
 *
 * These are intentionally tiny so tests and dev builds stay fast, but they are
 * real WAV/PNG containers that can be passed to real inference pipelines.
 */
object Fixtures {

    /**
     * Wraps generated 16-bit mono PCM in a WAV/RIFF container.
     *
     * The output size is bounded by [maxBytes]; if the generated PCM would
     * exceed it, the PCM is truncated before the header is written.
     */
    fun wavFromPcm(
        pcm: ByteArray,
        sampleRate: Int = 16000,
        maxBytes: Int = 2_000_000,
    ): ByteArray {
        val clamped = if (pcm.size > maxBytes - 44) {
            // Ensure the returned WAV (including the 44-byte header) does
            // not exceed maxBytes. Data must be an even number of bytes for
            // 16-bit samples.
            val dataSize = ((maxBytes - 44) / 2) * 2
            pcm.copyOf(dataSize)
        } else {
            pcm
        }
        return PcmToWav.fromPcm16(clamped, sampleRate = sampleRate, numChannels = 1, bitsPerSample = 16)
    }

    /** A 1x1 red PNG with valid headers and CRCs. */
    fun minimalPng(): ByteArray {
        val signature = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)

        fun chunk(type: ByteArray, data: ByteArray): ByteArray {
            val raw = type + data
            val crc = CRC32().apply { update(raw) }.value.toInt()
            return ByteBuffer.allocate(4 + raw.size + 4).apply {
                putInt(data.size)
                put(raw)
                putInt(crc)
            }.array()
        }

        val ihdr = chunk(
            "IHDR".toByteArray(Charsets.US_ASCII),
            ByteBuffer.allocate(13).apply {
                putInt(1) // width
                putInt(1) // height
                put(8)  // bit depth
                put(2)  // color type RGB
                put(0)  // compression
                put(0)  // filter
                put(0)  // interlace
            }.array()
        )

        val rawRow = byteArrayOf(0, 0xFF.toByte(), 0, 0) // filter=None + R=255 G=0 B=0
        val deflater = Deflater().apply { setInput(rawRow); finish() }
        val compressed = ByteArray(32)
        val compressedSize = deflater.deflate(compressed)
        val idatData = compressed.copyOf(compressedSize)
        val idat = chunk("IDAT".toByteArray(Charsets.US_ASCII), idatData)

        val iend = chunk("IEND".toByteArray(Charsets.US_ASCII), byteArrayOf())

        return signature + ihdr + idat + iend
    }

    /** A 1x1 grayscale JPEG. */
    fun minimalJpeg(): ByteArray = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(),
        0xFF.toByte(), 0xE0.toByte(), 0x00.toByte(), 0x10.toByte(),
        0x4A.toByte(), 0x46.toByte(), 0x49.toByte(), 0x46.toByte(), 0x00.toByte(),
        0x01.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(),
        0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(),
        0xFF.toByte(), 0xDB.toByte(), 0x00.toByte(), 0x43.toByte(), 0x00.toByte(),
    ) + ByteArray(64) { 0x01.toByte() } + byteArrayOf(
        0xFF.toByte(), 0xC0.toByte(), 0x00.toByte(), 0x0B.toByte(),
        0x08.toByte(), 0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x01.toByte(),
        0x01.toByte(), 0x01.toByte(), 0x11.toByte(), 0x00.toByte(),
        0xFF.toByte(), 0xC4.toByte(), 0x00.toByte(), 0x1F.toByte(), 0x00.toByte(),
    ) + ByteArray(16) { 0x00.toByte() } + byteArrayOf(
        0xFF.toByte(), 0xC4.toByte(), 0x00.toByte(), 0x1F.toByte(), 0x10.toByte(),
    ) + ByteArray(16) { 0x00.toByte() } + byteArrayOf(
        0xFF.toByte(), 0xDA.toByte(), 0x00.toByte(), 0x08.toByte(), 0x01.toByte(),
        0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0x3F.toByte(), 0x00.toByte(),
        0x7F.toByte(), 0x50.toByte(),
        0xFF.toByte(), 0xD9.toByte(),
    )
}
