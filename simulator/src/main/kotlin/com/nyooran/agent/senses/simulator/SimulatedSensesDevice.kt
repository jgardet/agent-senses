package com.nyooran.agent.senses.simulator

import com.nyooran.agent.senses.AudioCapture
import com.nyooran.agent.senses.AudioCaptureRequest
import com.nyooran.agent.senses.AudioFormat
import com.nyooran.agent.senses.AudioPlaybackRequest
import com.nyooran.agent.senses.AwaitInputRequest
import com.nyooran.agent.senses.BatteryState
import com.nyooran.agent.senses.ButtonEvent
import com.nyooran.agent.senses.DeviceFeature
import com.nyooran.agent.senses.DevicePresentation
import com.nyooran.agent.senses.DeviceState
import com.nyooran.agent.senses.DeviceTarget
import com.nyooran.agent.senses.DisconnectedEvent
import com.nyooran.agent.senses.ImageCapture
import com.nyooran.agent.senses.ImageCaptureRequest
import com.nyooran.agent.senses.ImageFormat
import com.nyooran.agent.senses.InputEvent
import com.nyooran.agent.senses.PresentationFormat
import com.nyooran.agent.senses.SensesDevice
import com.nyooran.agent.senses.SensesError
import com.nyooran.agent.senses.TapEvent
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min

/**
 * A minimal deterministic simulator that satisfies the [SensesDevice] contract
 * without Android, BLE, or firmware dependencies.
 *
 * It is intended for contract and adapter tests. Recorded fixtures are supplied
 * through [connect] and the playback queue.
 */
class SimulatedSensesDevice(
    private val backendName: String = "simulator",
) : SensesDevice {

    private val _state = MutableStateFlow(DeviceState(backendName = backendName))
    override val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<InputEvent>(extraBufferCapacity = 64)
    override val events = _events.asSharedFlow()

    private val lock = Mutex()
    private var connected = false

    override suspend fun connect(target: DeviceTarget?) = lock.withLock {
        _state.value = DeviceState(
            isConnected = true,
            isReady = true,
            target = target,
            supportedFeatures = setOf(
                DeviceFeature.CONNECT,
                DeviceFeature.AUDIO_CAPTURE,
                DeviceFeature.IMAGE_CAPTURE,
                DeviceFeature.PLAYBACK,
                DeviceFeature.PRESENTATION,
                DeviceFeature.INPUT,
                DeviceFeature.BATTERY,
            ),
            backendName = backendName,
        )
        connected = true
    }

    override suspend fun disconnect() = lock.withLock {
        _state.value = _state.value.copy(isConnected = false, isReady = false)
        _events.tryEmit(DisconnectedEvent)
        connected = false
    }

    override suspend fun captureAudio(request: AudioCaptureRequest): AudioCapture {
        ensureConnected()
        currentCoroutineContext().ensureActive()
        val duration = min(request.maxDurationMillis, 10_000)
        val sampleRate = 16000
        val bytes = min(duration * sampleRate / 1000 * 2, request.maxBytes.toLong()).toInt()
        return AudioCapture(
            audio = ByteArray(bytes) { (it % 256).toByte() },
            format = AudioFormat(sampleRate, 16, 1, "pcm-s16le", "audio/pcm"),
            durationMillis = duration,
        )
    }

    override suspend fun captureImage(request: ImageCaptureRequest): ImageCapture {
        ensureConnected()
        currentCoroutineContext().ensureActive()
        val size = min(request.resolution * request.resolution, request.maxBytes)
        return ImageCapture(
            image = ByteArray(size) { (it % 256).toByte() },
            format = ImageFormat("jpeg", "image/jpeg", request.resolution, request.resolution),
            isRaw = request.raw,
        )
    }

    override suspend fun awaitInput(request: AwaitInputRequest): InputEvent {
        ensureConnected()
        currentCoroutineContext().ensureActive()
        val event = withTimeoutOrNull(request.timeoutMillis) {
            _events.first { event ->
                event is TapEvent &&
                    event.source in request.acceptedSources &&
                    event.gesture in request.acceptedGestures
            }
        } ?: throw SensesError.Timeout("No matching input within ${request.timeoutMillis}ms")
        return event
    }

    override suspend fun playAudio(request: AudioPlaybackRequest) {
        ensureConnected()
        currentCoroutineContext().ensureActive()
        if (request.volume !in 0..100) throw SensesError.Rejected("volume must be 0..100")
    }

    override suspend fun present(request: DevicePresentation) {
        ensureConnected()
        currentCoroutineContext().ensureActive()
        if (request.format == PresentationFormat.CLEAR) return
        if (request.payload.isEmpty()) throw SensesError.Rejected("presentation payload is empty")
    }

    override suspend fun clearDisplay() = present(DevicePresentation(PresentationFormat.CLEAR))

    override suspend fun battery(): BatteryState {
        ensureConnected()
        currentCoroutineContext().ensureActive()
        return BatteryState(80, 4100, false)
    }

    suspend fun emit(event: InputEvent) = _events.emit(event)

    private fun ensureConnected() {
        if (!connected) throw SensesError.Disconnected("simulator is not connected")
    }
}
