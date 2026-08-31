package com.nyooran.agent.senses

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class StubSensesDevice : SensesDevice {
    private val _state = MutableStateFlow(DeviceState())
    override val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<InputEvent>(extraBufferCapacity = 64)
    override val events = _events.asSharedFlow()

    override suspend fun connect(target: DeviceTarget?) {
        _state.value = DeviceState(
            isConnected = true,
            isReady = true,
            target = target,
            supportedFeatures = DeviceFeature.entries.toSet(),
            backendName = "stub",
        )
    }

    override suspend fun disconnect() {
        _state.value = DeviceState()
    }

    override suspend fun captureAudio(request: AudioCaptureRequest): AudioCapture {
        if (!state.value.isConnected) throw SensesError.Disconnected("not connected")
        return AudioCapture(
            audio = ByteArray(10),
            format = AudioFormat(16000, 16, 1, "pcm-s16le", "audio/pcm"),
            durationMillis = request.maxDurationMillis,
        )
    }

    override suspend fun captureImage(request: ImageCaptureRequest) = ImageCapture(
        image = ByteArray(10),
        format = ImageFormat("jpeg", "image/jpeg", request.resolution, request.resolution),
        isRaw = request.deviceOptions["raw"] as? Boolean ?: false,
    )

    override suspend fun awaitInput(request: AwaitInputRequest): InputEvent {
        return withTimeoutOrNull(request.timeoutMillis) {
            _events.first { it is TapEvent && it.source in request.acceptedSources && it.gesture in request.acceptedGestures }
        } ?: throw SensesError.Timeout("no input")
    }

    override suspend fun playAudio(request: AudioPlaybackRequest) {}
    override suspend fun present(request: DevicePresentation) {}
    override suspend fun clearDisplay() {}
    override suspend fun battery() = BatteryState(80, 4100, false)

    suspend fun emit(event: InputEvent) = _events.emit(event)
}

class SensesContractTest {

    @Test
    fun contractCanBeImplementedWithoutAndroidOrHaloDependencies() = runTest {
        val device = StubSensesDevice()

        device.connect(DeviceTarget(name = "Halo-Test", address = "00:11:22:33:44:55"))

        assertTrue(device.state.value.isConnected)
        assertEquals("Halo-Test", device.state.value.target?.name)
        assertTrue(DeviceFeature.AUDIO_CAPTURE in device.state.value.supportedFeatures)

        val audio = device.captureAudio(AudioCaptureRequest(maxDurationMillis = 100, maxBytes = 4_096))
        assertEquals(16000, audio.format.sampleRate)
        assertTrue(audio.audio.size <= 4_096)

        val image = device.captureImage(ImageCaptureRequest(resolution = 256, maxBytes = 65_536))
        assertEquals(256, image.format.width)
        assertEquals("image/jpeg", image.format.mime)
        assertTrue(image.image.size <= 65_536)

        val battery = device.battery()
        assertEquals(80, battery.level)

        val inputJob = launch { device.emit(TapEvent("button", "single")) }
        val input = device.awaitInput(
            AwaitInputRequest(
                acceptedSources = setOf("button"),
                acceptedGestures = setOf("single"),
                timeoutMillis = 5_000,
            )
        )
        inputJob.join()

        assertEquals("button", input.source)
        assertEquals("single", input.gesture)

        device.disconnect()
        assertTrue(!device.state.value.isConnected)
    }

    @Test
    fun operationsFailWhenDisconnected() = runTest {
        val device = StubSensesDevice()

        assertFailsWith<SensesError.Disconnected> {
            device.captureAudio(AudioCaptureRequest(maxDurationMillis = 100, maxBytes = 1_000))
        }
    }
}
