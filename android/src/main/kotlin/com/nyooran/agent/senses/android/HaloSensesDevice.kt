package com.nyooran.agent.senses.android

import android.content.Context
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
import com.nyooran.agent.senses.ImageCapture
import com.nyooran.agent.senses.ImageCaptureRequest
import com.nyooran.agent.senses.ImageFormat
import com.nyooran.agent.senses.InputEvent
import com.nyooran.agent.senses.SensesDevice
import com.nyooran.agent.senses.SensesError
import com.nyooran.agent.senses.TapEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.milliseconds

/**
 * A Halo-specific [SensesDevice] that wraps [HaloConnectionManager].
 *
 * The device backend remains responsible for raw capture, playback,
 * presentation, and input. The legacy [HaloAudioConnection] is exposed
 * within the `com.nyooran.agent.senses.android` package so callers can be migrated in AS-021/024.
 */
class HaloSensesDevice(
    context: Context,
    private val scope: CoroutineScope,
) : SensesDevice {

    /** Legacy connection for incremental UI and service migration. */
    val connection: HaloConnectionManager = HaloConnectionManager(context, scope)

    internal val audioConnection: HaloAudioConnection = connection

    private val deviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(DeviceState(backendName = "halo"))
    override val state: StateFlow<DeviceState> = _state.asStateFlow()

    override val events: Flow<InputEvent> = connection.inputEvents.map { event ->
        when (event.source) {
            "button" -> ButtonEvent(event.source, event.gesture)
            else -> TapEvent(event.source, event.gesture)
        }
    }

    init {
        deviceScope.launch {
            connection.state.collect { value ->
                _state.value = when (value) {
                    is HaloConnectionState.Disconnected -> DeviceState(
                        isConnected = false,
                        isReady = false,
                        backendName = "halo",
                    )
                    is HaloConnectionState.Scanning -> DeviceState(
                        isConnected = false,
                        isReady = false,
                        backendName = "halo",
                    )
                    is HaloConnectionState.Connecting -> DeviceState(
                        isConnected = false,
                        isReady = false,
                        target = DeviceTarget(name = value.device.name, address = value.device.address),
                        backendName = "halo",
                    )
                    is HaloConnectionState.Failed -> DeviceState(
                        isConnected = false,
                        isReady = false,
                        backendName = "halo",
                    )
                    is HaloConnectionState.Ready -> DeviceState(
                        isConnected = true,
                        isReady = true,
                        target = DeviceTarget(name = value.device.name, address = value.device.address),
                        supportedFeatures = allFeatures,
                        backendName = "halo",
                    )
                }
            }
        }
    }

    override suspend fun connect(target: DeviceTarget?) {
        val address = target?.address
        val name = target?.name ?: "Halo"
        if (address.isNullOrBlank()) {
            throw SensesError.Rejected("A target device address is required to connect")
        }
        connection.connect(HaloDevice(address = address, name = name, rssi = 0))
    }

    override suspend fun disconnect() {
        connection.disconnect()
    }

    override suspend fun captureAudio(request: AudioCaptureRequest): AudioCapture {
        val wav = connection.listen(
            maxDuration = request.maxDurationMillis.milliseconds,
            gain = request.gain,
            aec = request.aec,
            voice = request.voice,
            maxBytes = request.maxBytes.toLong(),
        )
        val durationMs = estimateWavDurationMs(wav)
        return AudioCapture(
            audio = wav,
            format = AudioFormat(
                sampleRate = 16000,
                bitDepth = 16,
                channels = 1,
                encoding = "pcm-s16le",
                mime = "audio/wav",
            ),
            durationMillis = durationMs,
        )
    }

    override suspend fun captureImage(request: ImageCaptureRequest): ImageCapture {
        val jpeg = connection.capturePhoto(
            resolution = request.resolution,
            qualityIndex = request.qualityIndex,
            pan = request.pan,
            raw = request.raw,
            maxBytes = request.maxBytes.toLong(),
        )
        val actualResolution = if (request.resolution == 640) request.resolution else 640
        return ImageCapture(
            image = jpeg,
            format = ImageFormat(
                encoding = "jpeg",
                mime = "image/jpeg",
                width = actualResolution,
                height = actualResolution,
            ),
            isRaw = request.raw,
        )
    }

    override suspend fun awaitInput(request: AwaitInputRequest): InputEvent {
        val kind = request.acceptedGestures.firstOrNull()
        val event = connection.waitForTap(
            timeout = request.timeoutMillis.milliseconds,
            kind = kind,
        )
        return when (event.source) {
            "button" -> ButtonEvent(event.source, event.gesture)
            else -> TapEvent(event.source, event.gesture)
        }
    }

    override suspend fun playAudio(request: AudioPlaybackRequest) {
        audioConnection.playAudio(request)
    }

    override suspend fun present(request: DevicePresentation) = when (request.format) {
        com.nyooran.agent.senses.PresentationFormat.HSD -> {
            val json = Json.parseToJsonElement(request.payload.decodeToString())
            connection.render(json)
        }
        com.nyooran.agent.senses.PresentationFormat.HRP,
        com.nyooran.agent.senses.PresentationFormat.LUA,
        -> throw SensesError.Unavailable("Presentation format ${request.format} is not yet supported")
        com.nyooran.agent.senses.PresentationFormat.CLEAR -> connection.clear()
    }

    override suspend fun clearDisplay() = connection.clear()

    override suspend fun battery(): BatteryState {
        val battery = connection.battery()
        return BatteryState(
            level = battery.level,
            voltage = battery.voltage,
            charging = battery.charging,
        )
    }

    fun close() {
        deviceScope.cancel()
        connection.close()
    }

    private companion object {
        val allFeatures = setOf(
            DeviceFeature.CONNECT,
            DeviceFeature.AUDIO_CAPTURE,
            DeviceFeature.IMAGE_CAPTURE,
            DeviceFeature.PLAYBACK,
            DeviceFeature.PRESENTATION,
            DeviceFeature.INPUT,
            DeviceFeature.BATTERY,
        )

        fun estimateWavDurationMs(wav: ByteArray): Long {
            // A standard 16-bit mono WAV at 16 kHz has 32 bytes per millisecond.
            if (wav.size <= 44) return 0
            return ((wav.size - 44) / 32).toLong().coerceAtLeast(0)
        }
    }
}
