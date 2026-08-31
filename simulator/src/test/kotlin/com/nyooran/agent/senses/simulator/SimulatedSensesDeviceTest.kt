package com.nyooran.agent.senses.simulator

import com.nyooran.agent.senses.AudioCaptureRequest
import com.nyooran.agent.senses.AudioFormat
import com.nyooran.agent.senses.AudioPlaybackRequest
import com.nyooran.agent.senses.AwaitInputRequest
import com.nyooran.agent.senses.ButtonEvent
import com.nyooran.agent.senses.DeviceFeature
import com.nyooran.agent.senses.DeviceTarget
import com.nyooran.agent.senses.ImageCaptureRequest
import com.nyooran.agent.senses.SensesError
import com.nyooran.agent.senses.TapEvent
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SimulatedSensesDeviceTest {

    @Test
    fun happyPath() = runTest {
        val device = SimulatedSensesDevice()

        device.connect(DeviceTarget(name = "Halo-Test", address = "00:11:22:33:44:55"))

        assertTrue(device.state.value.isConnected)
        assertEquals("Halo-Test", device.state.value.target?.name)
        assertTrue(DeviceFeature.AUDIO_CAPTURE in device.state.value.supportedFeatures)

        val audio = device.captureAudio(AudioCaptureRequest(maxDurationMillis = 100, maxBytes = 4_096))
        assertEquals(16000, audio.format.sampleRate)
        assertTrue(audio.audio.size <= 4_096)

        val image = device.captureImage(ImageCaptureRequest(resolution = 256, maxBytes = 65_536))
        assertEquals(1, image.format.width)
        assertEquals(1, image.format.height)
        assertEquals("png", image.format.encoding)
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
        val device = SimulatedSensesDevice()

        assertFailsWith<SensesError.Disconnected> {
            device.captureAudio(AudioCaptureRequest(maxDurationMillis = 100, maxBytes = 1_000))
        }
    }

    @Test
    fun reconnectAfterDisconnect() = runTest {
        val device = SimulatedSensesDevice()

        device.connect(DeviceTarget(name = "Halo-Test", address = "00:11:22:33:44:55"))
        device.disconnect()

        device.connect(DeviceTarget(name = "Halo-Reconnect", address = "00:11:22:33:44:55"))
        assertTrue(device.state.value.isConnected)
        assertEquals("Halo-Reconnect", device.state.value.target?.name)

        val audio = device.captureAudio(AudioCaptureRequest(maxDurationMillis = 50, maxBytes = 2_048))
        assertTrue(audio.audio.size <= 2_048)
    }

    @Test
    fun acceptsButtonEvents() = runTest {
        val device = SimulatedSensesDevice()

        device.connect(DeviceTarget(name = "Halo-Test", address = "00:11:22:33:44:55"))

        val inputJob = launch { device.emit(ButtonEvent("side-button", "long")) }
        val input = device.awaitInput(
            AwaitInputRequest(
                acceptedSources = setOf("side-button"),
                acceptedGestures = setOf("long"),
                timeoutMillis = 5_000,
            )
        )
        inputJob.join()

        assertEquals("side-button", input.source)
        assertEquals("long", input.gesture)
    }

    @Test
    fun playAudioRecordsStartAndStop() = runTest {
        val device = SimulatedSensesDevice()

        device.connect(DeviceTarget(name = "Halo-Test", address = "00:11:22:33:44:55"))

        // 100ms of 16 kHz 16-bit mono PCM = 3200 bytes.
        val pcm = ByteArray(3200) { ((it * 137) % 256).toByte() }
        val request = AudioPlaybackRequest(
            audio = pcm,
            format = AudioFormat(16000, 16, 1, "pcm-s16le", "audio/pcm"),
            volume = 80,
        )

        device.playAudio(request)

        assertEquals(1, device.playbackStartCount)
        assertEquals(1, device.playbackStopCount)
        assertEquals(1, device.playbackCompletionCount)
        assertEquals(1, device.recordedAudio.size)
        assertEquals(request, device.recordedAudio.first())
    }

    @Test
    fun playAudioRejectsOversized() = runTest {
        val device = SimulatedSensesDevice()

        device.connect(DeviceTarget(name = "Halo-Test", address = "00:11:22:33:44:55"))

        val request = AudioPlaybackRequest(
            audio = ByteArray(SimulatedSensesDevice.MAX_PLAYBACK_BYTES + 1),
            format = AudioFormat(16000, 16, 1, "pcm-s16le", "audio/pcm"),
            volume = 80,
        )

        assertFailsWith<SensesError.LimitExceeded> {
            device.playAudio(request)
        }
        assertEquals(0, device.playbackStartCount)
        assertEquals(0, device.recordedAudio.size)
    }
}
