package com.nyooran.agent.senses.simulator

import com.nyooran.agent.senses.AudioCaptureRequest
import com.nyooran.agent.senses.AwaitInputRequest
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

        val image = device.captureImage(ImageCaptureRequest(resolution = 256, qualityIndex = 4, maxBytes = 65_536))
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
}
