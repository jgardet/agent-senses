package com.nyooran.agent.senses.simulator

import com.nyooran.agent.senses.BatteryState
import com.nyooran.agent.senses.DeviceFeature
import com.nyooran.agent.senses.DeviceTarget
import com.nyooran.agent.senses.ImageFormat
import com.nyooran.agent.senses.SensesError
import com.nyooran.agent.senses.TapEvent

/**
 * Pre-built [Scenario] fixtures for common agent-senses test paths.
 *
 * Each fixture is intentionally deterministic and avoids real sleeps. Combine
 * with a [kotlinx.coroutines.test.TestScope] for virtual-time tests.
 */
object ScenarioFixtures {

    /** A fully-featured simulator that is connected and ready immediately. */
    fun happyPath(
        backendName: String = "simulator",
        target: DeviceTarget = DeviceTarget(name = "Halo-Sim", address = "00:11:22:33:44:55"),
    ) = Scenario(
        backendName = backendName,
        target = target,
        supportedFeatures = DeviceFeature.entries.toSet(),
        battery = BatteryState(80, 4100, false),
    )

    /** The simulator takes [delayMillis] to become ready. */
    fun slowConnection(delayMillis: Long = 1_000) = Scenario(
        connectionDelayMillis = delayMillis,
    )

    /** The simulator disconnects automatically after [afterMillis]. */
    fun disconnectDuringOperation(afterMillis: Long = 100) = Scenario(
        disconnectAfterMillis = afterMillis,
    )

    /** An oversized image fixture that exceeds typical [maxBytes] limits. */
    fun oversizedImage(
        resolution: Int = 512,
        maxBytes: Int = 4_096,
    ): Scenario {
        val fixture = ByteArray(resolution * resolution) { (it % 256).toByte() }
        return Scenario(
            imageFixture = fixture,
            imageFormat = ImageFormat("jpeg", "image/jpeg", resolution, resolution),
        )
    }

    /** A capture request that the simulator rejects as a device error. */
    fun deviceError() = Scenario(
        audioError = SensesError.Unavailable("simulated audio subsystem failure"),
    )

    /** A single tap is delivered after [delayMillis]. */
    fun tapAfter(delayMillis: Long = 50, source: String = "button", gesture: String = "single") = Scenario(
        scheduledEvents = listOf(
            Scenario.ScheduledEvent(delayMillis, TapEvent(source, gesture, 0)),
        ),
    )
}
