package com.nyooran.agent.senses.android

import android.content.Context
import kotlinx.coroutines.CoroutineScope

/**
 * Factory for creating the application-level [SensesDevice] backend.
 *
 * Production builds always receive the physical Halo BLE backend. A simulated
 * backend can be selected in dev/debug builds through explicit configuration
 * (AS-024 follow-up).
 */
interface SensesDeviceFactory {
    fun create(context: Context, scope: CoroutineScope): SensesBackend

    data class SensesBackend(
        val device: com.nyooran.agent.senses.SensesDevice,
        /** Legacy audio connection for service migration (AS-021/024). */
        val audioConnection: HaloAudioConnection,
        /** Legacy connection manager for UI migration (AS-021/024). */
        val haloConnectionManager: HaloConnectionManager,
    ) {
        fun close() {
            (device as? HaloSensesDevice)?.close()
        }
    }
}

object DefaultSensesDeviceFactory : SensesDeviceFactory {
    override fun create(context: Context, scope: CoroutineScope): SensesDeviceFactory.SensesBackend {
        val device = HaloSensesDevice(context, scope)
        return SensesDeviceFactory.SensesBackend(
            device = device,
            audioConnection = device.audioConnection,
            haloConnectionManager = device.connection,
        )
    }
}
