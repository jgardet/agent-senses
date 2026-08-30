package com.nyooran.agent.senses.simulator

import com.nyooran.agent.senses.AudioCaptureRequest
import com.nyooran.agent.senses.AwaitInputRequest
import com.nyooran.agent.senses.ImageCaptureRequest
import com.nyooran.agent.senses.SensesError
import com.nyooran.agent.senses.TapEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ScenarioTest {

    @Test
    fun slowConnectionScenario() = runTest {
        val scenario = ScenarioFixtures.slowConnection(delayMillis = 500)
        val device = SimulatedSensesDevice(scenario, scope = this)

        assertFalse(device.state.value.isReady)

        val connectJob = launch { device.connect() }
        advanceTimeBy(500)
        assertFalse(device.state.value.isReady)
        advanceTimeBy(2)
        assertTrue(device.state.value.isReady)
        connectJob.join()
    }

    @Test
    fun disconnectAfterMillis() = runTest {
        val scenario = ScenarioFixtures.disconnectDuringOperation(afterMillis = 200)
        val device = SimulatedSensesDevice(scenario, scope = this)

        device.connect()
        assertTrue(device.state.value.isConnected)

        advanceTimeBy(201)
        assertFalse(device.state.value.isConnected)
    }

    @Test
    fun scheduledTapIsDelivered() = runTest {
        val scenario = ScenarioFixtures.tapAfter(delayMillis = 300)
        val device = SimulatedSensesDevice(scenario, scope = this)

        device.connect()

        val awaitJob = launch {
            val input = device.awaitInput(
                AwaitInputRequest(
                    acceptedSources = setOf("button"),
                    acceptedGestures = setOf("single"),
                    timeoutMillis = 5_000,
                )
            )
            assertEquals("button", input.source)
            assertEquals("single", input.gesture)
        }

        advanceTimeBy(300)
        assertTrue(awaitJob.isActive)
        advanceTimeBy(2)
        awaitJob.join()
    }

    @Test
    fun oversizedImageThrowsLimitExceeded() = runTest {
        val scenario = ScenarioFixtures.oversizedImage(resolution = 128, maxBytes = 4_096)
        val device = SimulatedSensesDevice(scenario, scope = this)

        device.connect()

        assertFailsWith<SensesError.LimitExceeded> {
            device.captureImage(
                ImageCaptureRequest(resolution = 128, qualityIndex = 4, maxBytes = 4_096)
            )
        }
    }

    @Test
    fun deviceErrorScenario() = runTest {
        val scenario = ScenarioFixtures.deviceError()
        val device = SimulatedSensesDevice(scenario, scope = this)

        device.connect()

        assertFailsWith<SensesError.Unavailable> {
            device.captureAudio(AudioCaptureRequest(maxDurationMillis = 100, maxBytes = 4_096))
        }
    }

    @Test
    fun audioCaptureHonoursMaxBytes() = runTest {
        val device = SimulatedSensesDevice(scope = this)
        device.connect()

        val capture = device.captureAudio(
            AudioCaptureRequest(maxDurationMillis = 1_000, maxBytes = 1_000)
        )
        assertTrue(capture.audio.size <= 1_000, "audio size ${capture.audio.size} exceeds maxBytes")
    }
}
