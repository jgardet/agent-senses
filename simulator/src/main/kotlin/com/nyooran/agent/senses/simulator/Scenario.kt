package com.nyooran.agent.senses.simulator

import com.nyooran.agent.senses.AudioFormat
import com.nyooran.agent.senses.BatteryState
import com.nyooran.agent.senses.DeviceFeature
import com.nyooran.agent.senses.DeviceTarget
import com.nyooran.agent.senses.ImageFormat
import com.nyooran.agent.senses.InputEvent
import com.nyooran.agent.senses.SensesError

/**
 * A deterministic, replayable script for [SimulatedSensesDevice].
 *
 * Every field has a safe default, so a scenario can be as simple as
 * `Scenario()` (the happy path) or as specific as a multi-step failure test.
 */
data class Scenario(
    val backendName: String = "simulator",
    val target: DeviceTarget? = DeviceTarget(name = "Simulated Halo", address = "00:00:00:00:00:00"),
    val supportedFeatures: Set<DeviceFeature> = DeviceFeature.entries.toSet(),
    val battery: BatteryState = BatteryState(80, 4100, false),
    val connectionDelayMillis: Long = 0,
    val connectionError: SensesError? = null,
    val disconnectAfterMillis: Long? = null,
    val audioDelayMillis: Long = 0,
    val audioFormat: AudioFormat = AudioFormat(16000, 16, 1, "pcm-s16le", "audio/pcm"),
    val audioFixture: ByteArray = ByteArray(0),
    val audioError: SensesError? = null,
    val imageDelayMillis: Long = 0,
    val imageFormat: ImageFormat = ImageFormat("jpeg", "image/jpeg", 256, 256),
    val imageFixture: ByteArray = ByteArray(0),
    val imageError: SensesError? = null,
    val playAudioRejection: SensesError? = null,
    val scheduledEvents: List<ScheduledEvent> = emptyList(),
) {
    data class ScheduledEvent(
        val delayMillis: Long,
        val event: InputEvent,
    )
}
