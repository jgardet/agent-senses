package com.nyooran.agent.senses.simulator

import com.nyooran.agent.senses.BatteryState
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
 * This lets `HaloSession` and `PhysicalHaloEndpoint` tests run without Android
 * Bluetooth or a physical device by simulating the message stream, chunking,
 * and ACK behavior that the firmware would produce.
 *
 * Optional live-media hooks let a host app back the virtual device with real
 * hardware (e.g. the phone microphone/camera/speaker) so the full
 * `PhysicalHaloEndpoint` path can be exercised end-to-end:
 * - [microphoneSource] is invoked after `MICROPHONE_START`; it streams PCM
 *   chunks through its `emit` callback until it returns or the stream is
 *   cancelled by `MICROPHONE_STOP`. Already-emitted chunks stay delivered.
 * - [photoSource] is invoked on `CAPTURE_PHOTO` and returns the full JPEG.
 * - [speakerSessionStart]/[speakerSessionEnd] bracket `SPEAKER_START`/`STOP`
 *   and [speakerSink] receives each `sendAudioFrame` payload.
 */
class SimulatedHaloBleTransport(
    private val scenario: Scenario = Scenario(),
    private val timeSource: TimeSource = WallClock,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val microphoneSource: (suspend (emit: suspend (ByteArray) -> Unit) -> Unit)? = null,
    private val photoSource: (suspend () -> ByteArray)? = null,
    private val speakerSink: (suspend (ByteArray) -> Unit)? = null,
    private val speakerSessionStart: (suspend (ByteArray) -> Unit)? = null,
    private val speakerSessionEnd: (suspend () -> Unit)? = null,
) : HaloBleTransport {

    private val transportScope = CoroutineScope(scope.coroutineContext + SupervisorJob())
    private val lock = Mutex()
    private val batteryOverride = AtomicReference<BatteryState?>(null)

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
            HaloProtocol.MICROPHONE_START -> startMicStream(payload)
            HaloProtocol.MICROPHONE_STOP -> stopActiveStream(HaloProtocol.AUDIO_FINAL)
            HaloProtocol.CAPTURE_PHOTO -> startPhotoStream(payload)
            HaloProtocol.SPEAKER_START -> {
                speakerSessionStart?.invoke(payload)
                _messages.tryEmit(HaloMessage(HaloProtocol.STATUS, byteArrayOf(0)))
            }
            HaloProtocol.SPEAKER_STOP -> {
                speakerSessionEnd?.invoke()
                _messages.tryEmit(HaloMessage(HaloProtocol.STATUS, byteArrayOf(0)))
            }
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
        speakerSink?.invoke(frame)
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

    // ------------------------------------------------------------------
    // Device-initiated injection (taps, buttons, battery, disconnect)
    // ------------------------------------------------------------------

    /**
     * Emit a device-to-host message as the firmware would (e.g. a tap event).
     * Returns false when the transport is not connected — real hardware cannot
     * report events while disconnected.
     */
    fun injectDeviceMessage(code: Int, payload: ByteArray = byteArrayOf()): Boolean {
        if (!connected.get()) return false
        return _messages.tryEmit(HaloMessage(code, payload))
    }

    /** Emit a temple-tap event. [gestureCode] follows the firmware encoding: 1 single, 2 double, 3 triple. */
    fun injectTap(gestureCode: Int = 1): Boolean =
        injectDeviceMessage(HaloProtocol.TAP, byteArrayOf(gestureCode.toByte()))

    /** Emit a physical-button event. [gestureCode] follows the firmware encoding: 1 single, 2 double, 3 long. */
    fun injectButton(gestureCode: Int = 1): Boolean =
        injectDeviceMessage(HaloProtocol.BUTTON, byteArrayOf(gestureCode.toByte()))

    /**
     * Simulate the wearable disconnecting: active streams are cancelled and a
     * disconnect event is emitted without tearing down the transport scope.
     */
    fun simulateDisconnect() {
        connected.set(false)
        _connectionEvents.tryEmit(false)
        activeStream.getAndSet(null)?.cancel()
        _messages.tryEmit(HaloMessage(HaloProtocol.STATUS, byteArrayOf(0)))
    }

    /** Override the battery state reported by `DEVICE_STATUS` responses. */
    fun setBattery(level: Int, voltage: Int, charging: Boolean) {
        batteryOverride.set(BatteryState(level, voltage, charging))
    }

    private fun startMicStream(request: ByteArray) {
        val source = microphoneSource
        if (source == null) {
            startStream(request, HaloProtocol.AUDIO_CHUNK, HaloProtocol.AUDIO_FINAL) { audioFixture() }
            return
        }
        activeStream.getAndSet(null)?.cancel()
        val job = transportScope.launch {
            source.invoke { chunk ->
                _messages.tryEmit(HaloMessage(HaloProtocol.AUDIO_CHUNK, chunk))
            }
            _messages.tryEmit(HaloMessage(HaloProtocol.AUDIO_FINAL, byteArrayOf()))
        }
        activeStream.set(job)
    }

    private fun startPhotoStream(request: ByteArray) {
        val source = photoSource
        if (source == null) {
            startStream(request, HaloProtocol.PHOTO_JPEG, HaloProtocol.PHOTO_FINAL) { imageFixture() }
            return
        }
        activeStream.getAndSet(null)?.cancel()
        val job = transportScope.launch {
            val chunks = source.invoke().toList().chunked(maxDataPayload).map { bytes ->
                ByteArray(bytes.size) { bytes[it] }
            }
            for (chunk in chunks) {
                _messages.tryEmit(HaloMessage(HaloProtocol.PHOTO_JPEG, chunk))
            }
            _messages.tryEmit(HaloMessage(HaloProtocol.PHOTO_FINAL, byteArrayOf()))
        }
        activeStream.set(job)
    }

    private fun startStream(
        request: ByteArray,
        chunkCode: Int,
        finalCode: Int,
        fixture: suspend () -> ByteArray,
    ) {
        activeStream.getAndSet(null)?.cancel()
        val job = transportScope.launch {
            val chunks = fixture().toList().chunked(maxDataPayload).map { bytes ->
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
            val sampleCount = format.sampleRate
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
        val battery = batteryOverride.get() ?: scenario.battery
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
