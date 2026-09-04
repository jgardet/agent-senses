package com.nyooran.agent.senses

/**
 * Shared media types and device descriptors used by the Phase 2
 * [SenseEndpoint] contract. These DTOs are free of Android, BLE, Ktor,
 * Node, Python, firmware, and model types.
 */

enum class DeviceFeature {
    AUDIO_CAPTURE,
    IMAGE_CAPTURE,
    PLAYBACK,
    PRESENTATION,
    INPUT,
    BATTERY,
    CONNECT,
}

data class DeviceTarget(
    val name: String? = null,
    val address: String? = null,
)

data class AudioFormat(
    val sampleRate: Int,
    val bitDepth: Int,
    val channels: Int,
    val encoding: String,
    val mime: String,
)

data class ImageFormat(
    val encoding: String,
    val mime: String,
    val width: Int,
    val height: Int,
)

data class BatteryState(
    val level: Int,
    val voltage: Int,
    val charging: Boolean,
)

sealed class SensesError(
    val category: Category,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    enum class Category {
        Unavailable,
        Disconnected,
        Timeout,
        Cancelled,
        Rejected,
        LimitExceeded,
        Protocol,
        PermissionDenied,
        ModelUnavailable,
        Internal,
    }

    class Unavailable(message: String, cause: Throwable? = null) : SensesError(Category.Unavailable, message, cause)
    class Disconnected(message: String, cause: Throwable? = null) : SensesError(Category.Disconnected, message, cause)
    class Timeout(message: String, cause: Throwable? = null) : SensesError(Category.Timeout, message, cause)
    class Cancelled(message: String, cause: Throwable? = null) : SensesError(Category.Cancelled, message, cause)
    class Rejected(message: String, cause: Throwable? = null) : SensesError(Category.Rejected, message, cause)
    class LimitExceeded(message: String, cause: Throwable? = null) : SensesError(Category.LimitExceeded, message, cause)
    class Protocol(message: String, cause: Throwable? = null) : SensesError(Category.Protocol, message, cause)
    class PermissionDenied(message: String, cause: Throwable? = null) : SensesError(Category.PermissionDenied, message, cause)
    class ModelUnavailable(message: String, cause: Throwable? = null) : SensesError(Category.ModelUnavailable, message, cause)
    class Internal(message: String, cause: Throwable? = null) : SensesError(Category.Internal, message, cause)
}
