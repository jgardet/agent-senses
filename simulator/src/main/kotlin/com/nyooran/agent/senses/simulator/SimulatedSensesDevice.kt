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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min

/**
 * A deterministic, scripted simulator that satisfies the [SensesDevice] contract
 * without Android, BLE, or firmware dependencies.
 *
 * The simulator runs against a [Scenario] that controls connection timing,
 * supported features, fixtures, scheduled input events, and failure modes. It
 * is intended for contract, adapter, and workflow tests.
 *
 * The default [Scenario] and [Dispatchers.Default] produce a fast, safe
 * simulator. For virtual-time tests, pass a [CoroutineScope] backed by a
 * [kotlinx.coroutines.test.StandardTestDispatcher].
 */
class SimulatedSensesDevice(
    private val scenario: Scenario = Scenario(),
    private val timeSource: TimeSource = WallClock,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : SensesDevice {

    private val deviceScope = CoroutineScope(scope.coroutineContext + SupervisorJob())
    private val lock = Mutex()

    private val _state = MutableStateFlow(DeviceState(backendName = scenario.backendName))
    override val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<InputEvent>(extraBufferCapacity = 64)
    override val events = _events.asSharedFlow()

    private var connected = false

    override suspend fun connect(target: DeviceTarget?) = lock.withLock {
        connected = false
        _state.value = DeviceState(
            isConnected = false,
            isReady = false,
            target = null,
            supportedFeatures = emptySet(),
            backendName = scenario.backendName,
        )

        if (scenario.connectionDelayMillis > 0) {
            delay(scenario.connectionDelayMillis)
        }
        currentCoroutineContext().ensureActive()

        val error = scenario.connectionError
        if (error != null) {
            _state.value = DeviceState(
                isConnected = false,
                isReady = false,
                target = target ?: scenario.target,
                backendName = scenario.backendName,
            )
            throw error
        }

        connected = true
        _state.value = DeviceState(
            isConnected = true,
            isReady = true,
            target = target ?: scenario.target,
            supportedFeatures = scenario.supportedFeatures,
            backendName = scenario.backendName,
        )

        scheduleDisconnect()
        scheduleEvents()
    }

    override suspend fun disconnect() = lock.withLock {
        connected = false
        deviceScope.cancel()
        _state.value = _state.value.copy(isConnected = false, isReady = false)
        _events.tryEmit(DisconnectedEvent)
        Unit
    }

    override suspend fun captureAudio(request: AudioCaptureRequest): AudioCapture {
        ensureConnected()
        currentCoroutineContext().ensureActive()

        val error = scenario.audioError
        if (error != null) throw error

        if (scenario.audioDelayMillis > 0) {
            delay(scenario.audioDelayMillis.coerceAtMost(request.maxDurationMillis))
        }
        currentCoroutineContext().ensureActive()

        val bytes = if (scenario.audioFixture.isNotEmpty()) {
            scenario.audioFixture
        } else {
            generatePcm(request)
        }

        if (bytes.size > request.maxBytes) {
            throw SensesError.LimitExceeded(
                "audio capture of ${bytes.size} bytes exceeds the request limit of ${request.maxBytes}"
            )
        }

        val durationMs = estimateDurationMillis(bytes.size, scenario.audioFormat)
        return AudioCapture(
            audio = bytes,
            format = scenario.audioFormat,
            durationMillis = durationMs,
        )
    }

    override suspend fun captureImage(request: ImageCaptureRequest): ImageCapture {
        ensureConnected()
        currentCoroutineContext().ensureActive()

        val error = scenario.imageError
        if (error != null) throw error

        if (scenario.imageDelayMillis > 0) {
            delay(scenario.imageDelayMillis)
        }
        currentCoroutineContext().ensureActive()

        val bytes = if (scenario.imageFixture.isNotEmpty()) {
            scenario.imageFixture
        } else {
            generateImageBytes(request)
        }

        if (bytes.size > request.maxBytes) {
            throw SensesError.LimitExceeded(
                "image capture of ${bytes.size} bytes exceeds the request limit of ${request.maxBytes}"
            )
        }

        return ImageCapture(
            image = bytes,
            format = ImageFormat(
                encoding = scenario.imageFormat.encoding,
                mime = scenario.imageFormat.mime,
                width = request.resolution,
                height = request.resolution,
            ),
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

        val error = scenario.playAudioRejection
        if (error != null) throw error

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
        return scenario.battery
    }

    /**
     * Manually emit an input event. Useful when a test wants to drive timing
     * itself rather than using [Scenario.ScheduledEvent].
     */
    suspend fun emit(event: InputEvent) = _events.emit(event)

    /** Cancel any pending scheduled events and release the device scope. */
    fun close() {
        deviceScope.cancel()
    }

    private fun ensureConnected() {
        if (!connected) throw SensesError.Disconnected("simulator is not connected")
    }

    private fun scheduleDisconnect() {
        val after = scenario.disconnectAfterMillis ?: return
        deviceScope.launch {
            delay(after)
            disconnect()
        }
    }

    private fun scheduleEvents() {
        for ((delayMillis, event) in scenario.scheduledEvents) {
            deviceScope.launch {
                delay(delayMillis)
                _events.tryEmit(event)
            }
        }
    }

    private fun generatePcm(request: AudioCaptureRequest): ByteArray {
        val format = scenario.audioFormat
        val bytesPerSample = format.bitDepth / 8
        val sampleCount = (request.maxDurationMillis * format.sampleRate / 1000).toInt()
        val pcmSize = sampleCount * bytesPerSample * format.channels
        val size = min(pcmSize, request.maxBytes)
        return ByteArray(size) { ((it * 137) % 256).toByte() }
    }

    private fun generateImageBytes(request: ImageCaptureRequest): ByteArray {
        val size = min(request.resolution * request.resolution, request.maxBytes)
        return ByteArray(size) { (it % 256).toByte() }
    }

    private fun estimateDurationMillis(byteCount: Int, format: AudioFormat): Long {
        val bytesPerSample = format.bitDepth / 8
        val bytesPerFrame = bytesPerSample * format.channels
        if (bytesPerFrame <= 0) return 0
        return (byteCount / bytesPerFrame) * 1000L / format.sampleRate
    }
}
