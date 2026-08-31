package com.nyooran.agent.senses.simulator

import com.nyooran.agent.senses.DeviceFeature
import halo.engine.HaloBleTransport
import halo.engine.HaloMessage
import halo.engine.HaloProtocol
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A protocol-level virtual BLE transport for the Halo engine.
 *
 * This lets `HaloSession` and `HaloConnectionManager` tests run without Android
 * Bluetooth or a physical device by simulating the message stream, chunking,
 * and ACK behavior that the firmware would produce.
 */
class SimulatedHaloBleTransport(
    private val scenario: Scenario = Scenario(),
    private val timeSource: TimeSource = WallClock,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : HaloBleTransport {

    private val transportScope = CoroutineScope(scope.coroutineContext + SupervisorJob())
    private val lock = Mutex()

    private val _messages = MutableSharedFlow<HaloMessage>(extraBufferCapacity = 128)
    override val messages = _messages.asSharedFlow()

    private val _connectionEvents = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    override val connectionEvents = _connectionEvents.asSharedFlow()

    private val connected = AtomicBoolean(false)
    private val activeStream = AtomicReference<Job?>(null)
    private val packetCount = AtomicInteger(0)

    private val _recordedLua = mutableListOf<String>()
    private val _recordedHrp = mutableListOf<ByteArray>()
    private val _recordedAudioFrames = mutableListOf<ByteArray>()
    private val _speakerAudio = ByteArrayOutputStream()

    override val supportsAudio: Boolean
        get() = scenario.supportedFeatures.contains(DeviceFeature.PLAYBACK)

    override val maxLuaPayload: Int = scenario.maxLuaPayload
    override val maxDataPayload: Int = scenario.maxDataPayload

    override suspend fun connect(name: String?) = lock.withLock {
        connected.set(false)
        _connectionEvents.tryEmit(false)
        if (scenario.connectionDelayMillis > 0) {
            delay(scenario.connectionDelayMillis)
        }
        connected.set(true)
        _connectionEvents.tryEmit(true)
        _messages.tryEmit(HaloMessage(HaloProtocol.STATUS, byteArrayOf(0)))
        scheduleBatteryStatus()
    }

    override suspend fun disconnect() = lock.withLock {
        connected.set(false)
        _connectionEvents.tryEmit(false)
        activeStream.getAndSet(null)?.cancel()
        transportScope.coroutineContext[Job]?.cancel()
        _messages.tryEmit(HaloMessage(HaloProtocol.STATUS, byteArrayOf(0)))
        Unit
    }

    override suspend fun sendLua(lua: String) {
        if (!applyReliability()) return
        _recordedLua.add(lua)
        _messages.tryEmit(HaloMessage(HaloProtocol.STATUS, byteArrayOf(0)))
    }

    override suspend fun sendMessage(code: Int, payload: ByteArray) {
        if (!connected.get()) return
        if (!applyReliability()) return
        when (code) {
            HaloProtocol.MICROPHONE_START -> startStream(payload, chunkCode = HaloProtocol.AUDIO_CHUNK, finalCode = HaloProtocol.AUDIO_FINAL, fixture = audioFixture())
            HaloProtocol.MICROPHONE_STOP -> stopActiveStream(HaloProtocol.AUDIO_FINAL)
            HaloProtocol.CAPTURE_PHOTO -> startStream(payload, chunkCode = HaloProtocol.PHOTO_JPEG, finalCode = HaloProtocol.PHOTO_FINAL, fixture = imageFixture())
            HaloProtocol.SPEAKER_START -> _messages.tryEmit(HaloMessage(HaloProtocol.STATUS, byteArrayOf(0)))
            HaloProtocol.SPEAKER_STOP -> _messages.tryEmit(HaloMessage(HaloProtocol.STATUS, byteArrayOf(0)))
            HaloProtocol.DEVICE_STATUS -> _messages.tryEmit(HaloMessage(HaloProtocol.DEVICE_STATUS, batteryPayload()))
            HaloProtocol.HRP -> _messages.tryEmit(HaloMessage(HaloProtocol.STATUS, byteArrayOf(0)))
            HaloProtocol.STATUS -> _messages.tryEmit(HaloMessage(HaloProtocol.STATUS, payload))
            HaloProtocol.ERROR -> _messages.tryEmit(HaloMessage(HaloProtocol.ERROR, payload))
            else -> _messages.tryEmit(HaloMessage(HaloProtocol.STATUS, payload))
        }
    }

    override suspend fun sendData(bytes: ByteArray) {
        if (!applyReliability()) return
        _recordedHrp.add(bytes)
        _messages.tryEmit(HaloMessage(HaloProtocol.STATUS, byteArrayOf(0)))
    }

    override suspend fun sendAudioFrame(frame: ByteArray) {
        if (!applyReliability()) return
        _recordedAudioFrames.add(frame)
        _speakerAudio.write(frame)
    }

    /** Cancel any pending scheduled events and release the transport scope. */
    fun close() {
        connected.set(false)
        _connectionEvents.tryEmit(false)
        activeStream.getAndSet(null)?.cancel()
        transportScope.cancel()
    }

    /** Lua strings recorded by [sendLua]. */
    val recordedLua: List<String> get() = _recordedLua.toList()

    /** HRP display payloads recorded by [sendData]. */
    val recordedHrp: List<ByteArray> get() = _recordedHrp.map { it.copyOf() }

    /** Speaker PCM/LC3 frames recorded by [sendAudioFrame]. */
    val recordedAudioFrames: List<ByteArray> get() = _recordedAudioFrames.map { it.copyOf() }

    /** Concatenated speaker audio bytes. */
    val speakerAudio: ByteArray get() = _speakerAudio.toByteArray()

    private fun startStream(
        request: ByteArray,
        chunkCode: Int,
        finalCode: Int,
        fixture: ByteArray,
    ) {
        activeStream.getAndSet(null)?.cancel()
        val job = transportScope.launch {
            val chunks = fixture.toList().chunked(maxDataPayload).map { bytes ->
                ByteArray(bytes.size) { bytes[it] }
            }
            val chunkDelay = if (chunks.isNotEmpty()) scenario.audioDelayMillis / chunks.size else 0
            for (chunk in chunks) {
                if (chunkDelay > 0) delay(chunkDelay)
                _messages.tryEmit(HaloMessage(chunkCode, chunk))
            }
            _messages.tryEmit(HaloMessage(finalCode, byteArrayOf()))
        }
        activeStream.set(job)
    }

    private fun stopActiveStream(finalCode: Int) {
        activeStream.getAndSet(null)?.cancel()
        _messages.tryEmit(HaloMessage(finalCode, byteArrayOf()))
    }

    /**
     * Apply the scenario's reliability policy to the next host-to-device packet.
     * Returns `false` when the packet is dropped.
     */
    private suspend fun applyReliability(): Boolean {
        scenario.packetRejection?.let { throw it }
        if (scenario.packetDelayMillis > 0) {
            delay(scenario.packetDelayMillis)
        }
        val count = packetCount.incrementAndGet()
        return count > scenario.droppedPacketCount
    }

    private fun audioFixture(): ByteArray {
        return if (scenario.audioFixture.isNotEmpty()) {
            scenario.audioFixture
        } else {
            val format = scenario.audioFormat
            val bytesPerSample = format.bitDepth / 8
            val sampleCount = (1000 * format.sampleRate / 1000).toInt()
            val size = sampleCount * bytesPerSample * format.channels
            ByteArray(size.coerceAtLeast(512).coerceAtMost(32_768)) { ((it * 137) % 256).toByte() }
        }
    }

    private fun imageFixture(): ByteArray {
        return if (scenario.imageFixture.isNotEmpty()) {
            scenario.imageFixture
        } else {
            ByteArray(8_192) { (it % 256).toByte() }
        }
    }

    private fun batteryPayload(): ByteArray {
        val battery = scenario.battery
        return byteArrayOf(
            (battery.level and 0xff).toByte(),
            ((battery.voltage ushr 8) and 0xff).toByte(),
            (battery.voltage and 0xff).toByte(),
            if (battery.charging) 1.toByte() else 0.toByte(),
        )
    }

    private fun scheduleBatteryStatus() {
        transportScope.launch {
            delay(1_000)
            _messages.tryEmit(HaloMessage(HaloProtocol.DEVICE_STATUS, batteryPayload()))
        }
    }
}
