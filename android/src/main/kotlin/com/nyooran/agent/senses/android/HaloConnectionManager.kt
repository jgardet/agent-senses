package com.nyooran.agent.senses.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.nyooran.agent.senses.AudioFormat
import com.nyooran.agent.senses.AudioPlaybackRequest
import com.nyooran.agent.senses.SensesError
import com.nyooran.agent.senses.android.audio.WavReader
import com.nyooran.agent.senses.audio.PcmToWav
import halo.engine.AndroidBleTransport
import halo.engine.AndroidSpritePacker
import halo.engine.BluetoothGattChannel
import halo.engine.HaloHost
import halo.engine.HaloNotification
import halo.engine.HaloLimitException
import halo.engine.HaloProtocol
import halo.engine.HaloRuntimeInstaller
import halo.engine.HaloSession
import halo.engine.HaloTransportException
import halo.engine.HsdHrpCompiler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

sealed interface HaloConnectionState {
    data object Disconnected : HaloConnectionState
    data object Scanning : HaloConnectionState
    data class Connecting(val device: HaloDevice) : HaloConnectionState
    data class Ready(val device: HaloDevice, val runtime: String) : HaloConnectionState
    data class Failed(val message: String) : HaloConnectionState
}

data class HaloDevice(val address: String, val name: String, val rssi: Int)
data class HaloInputEvent(val source: String, val gesture: String)
data class HaloBattery(val level: Int, val voltage: Int, val charging: Boolean)

/**
 * Hard ceiling for microphone PCM, generous enough for 30 s at 16 kHz
 * 16-bit mono (≈ 960 KiB) while still preventing unbounded growth.
 */
internal const val MAX_MIC_BYTES: Long = 1_500_000

/**
 * Hard ceiling for a JPEG photo transfer. A 720×720 high-quality
 * capture fits comfortably inside this; the runtime enforces a
 * per-message cap of [HaloProtocol.MAX_DATA_BYTES].
 */
internal const val MAX_PHOTO_BYTES: Long = 3_000_000

interface HaloDisplayConnection {
    val state: StateFlow<HaloConnectionState>
    suspend fun render(scene: JsonElement)
    suspend fun clear()
}

interface HaloAudioConnection : HaloDisplayConnection {
    suspend fun listen(maxDuration: Duration = 30.seconds, gain: Int = 0, aec: Boolean = true, voice: Boolean = true): ByteArray
    suspend fun capturePhoto(resolution: Int = 512, qualityIndex: Int = 4, pan: Int = 0, raw: Boolean = false, maxBytes: Long = MAX_PHOTO_BYTES): ByteArray
    suspend fun battery(): HaloBattery
    suspend fun waitForTap(timeout: Duration = 30.seconds, kind: String? = null): HaloInputEvent
    suspend fun speak(text: String, volume: Int = 80)
    suspend fun playAudio(request: AudioPlaybackRequest)
}

class HaloConnectionManager(
    context: Context,
    private val scope: CoroutineScope,
) : HaloAudioConnection {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter get() = bluetoothManager?.adapter
    private val _state = MutableStateFlow<HaloConnectionState>(HaloConnectionState.Disconnected)
    override val state: StateFlow<HaloConnectionState> = _state.asStateFlow()
    private val _devices = MutableStateFlow<List<HaloDevice>>(emptyList())
    val devices: StateFlow<List<HaloDevice>> = _devices.asStateFlow()
    private val _inputEvents = MutableSharedFlow<HaloInputEvent>(extraBufferCapacity = 16)
    val inputEvents: SharedFlow<HaloInputEvent> = _inputEvents.asSharedFlow()
    private val operationMutex = Mutex()
    private val tapMutex = Mutex()
    private var scanJob: Job? = null
    private var scanCallback: ScanCallback? = null
    private var connectJob: Job? = null
    private var connectionJob: Job? = null
    private var inputJob: Job? = null
    private var transport: AndroidBleTransport? = null
    private var host: HaloHost? = null
    private var textToSpeech: TextToSpeech? = null
    private val ttsReady = CompletableDeferred<Int>()

    @SuppressLint("MissingPermission")
    fun startScan() {
        stopScan()
        if (adapter?.isEnabled == false) {
            _state.value = HaloConnectionState.Failed("Bluetooth is turned off")
            return
        }
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            _state.value = HaloConnectionState.Failed("Bluetooth LE is unavailable")
            return
        }
        _devices.value = emptyList()
        _state.value = HaloConnectionState.Scanning
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = HaloDevice(
                    address = result.device.address,
                    name = result.device.name ?: result.scanRecord?.deviceName ?: "Halo",
                    rssi = result.rssi,
                )
                _devices.value = (_devices.value.filterNot { it.address == device.address } + device)
                    .sortedByDescending { it.rssi }
            }

            override fun onScanFailed(errorCode: Int) {
                _state.value = HaloConnectionState.Failed("Halo scan failed: $errorCode")
            }
        }
        scanCallback = callback
        runCatching {
            scanner.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(BluetoothGattChannel.SERVICE_UUID)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                callback,
            )
        }.onFailure { _state.value = HaloConnectionState.Failed(it.message ?: "Unable to start Halo scan") }
        scanJob = scope.launch {
            delay(10_000)
            stopScan()
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        scanCallback?.let { callback -> runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) } }
        scanCallback = null
        if (_state.value is HaloConnectionState.Scanning) _state.value = HaloConnectionState.Disconnected
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(device: HaloDevice) = withContext(Dispatchers.IO) {
        val job = coroutineContext[Job]!!
        connectJob = job
        try {
            operationMutex.withLock {
                stopScan()
                disconnectLocked()
                _state.value = HaloConnectionState.Connecting(device)
                try {
                    val bluetoothDevice = requireNotNull(adapter?.getRemoteDevice(device.address)) { "Bluetooth device is unavailable" }
                    val callbacks = BluetoothGattChannel.Callbacks()
                    val gatt = bluetoothDevice.connectGatt(appContext, false, callbacks)
                    currentCoroutineContext().ensureActive()
                    val channel = try {
                        callbacks.awaitChannel()
                    } catch (error: Exception) {
                        gatt.close()
                        throw error
                    }
                    currentCoroutineContext().ensureActive()
                    val connectedTransport = AndroidBleTransport(channel)
                    transport = connectedTransport
                    connectionJob = scope.launch {
                        connectedTransport.notifications.filterIsInstance<HaloNotification.Disconnected>().first()
                        operationMutex.withLock {
                            if (transport === connectedTransport) {
                                inputJob?.cancel()
                                inputJob = null
                                host = null
                                transport = null
                                _state.value = HaloConnectionState.Failed("Halo disconnected")
                                runCatching { connectedTransport.disconnect() }
                            }
                        }
                    }
                    inputJob = scope.launch {
                        connectedTransport.notifications.filterIsInstance<HaloNotification.Message>().collect { message ->
                            val source = when (message.code) {
                                HaloProtocol.BUTTON -> "button"
                                HaloProtocol.TAP -> "tap"
                                else -> return@collect
                            }
                            val gesture = when (message.payload.firstOrNull()?.toInt()) {
                                1 -> "single"
                                2 -> "double"
                                3 -> if (source == "button") "long" else "triple"
                                else -> "unknown"
                            }
                            _inputEvents.emit(HaloInputEvent(source, gesture))
                        }
                    }
                    connectedTransport.connect(device.name)
                    currentCoroutineContext().ensureActive()
                    val source = appContext.assets.open("he_runtime.lua").bufferedReader().use { it.readText() }
                    val runtime = HaloRuntimeInstaller(connectedTransport).installAndStart(source)
                    currentCoroutineContext().ensureActive()
                    val packer = AndroidSpritePacker(resolveAsset = { src: String ->
                        if (src.startsWith("asset://")) {
                            runCatching { appContext.assets.open(src.removePrefix("asset://")).use { it.readBytes() } }.getOrNull()
                        } else null
                    })
                    host = HaloHost(connectedTransport, hrpCompiler = HsdHrpCompiler(packer))
                    _state.value = HaloConnectionState.Ready(device, runtime)
                } catch (cancelled: CancellationException) {
                    disconnectLocked()
                    _state.value = HaloConnectionState.Disconnected
                    throw cancelled
                } catch (error: Exception) {
                    disconnectLocked()
                    _state.value = HaloConnectionState.Failed(error.message ?: "Halo connection failed")
                }
            }
        } finally {
            if (connectJob === job) connectJob = null
        }
    }

    override suspend fun render(scene: JsonElement) = operationMutex.withLock {
        val renderer = requireNotNull(host) { "Halo is not connected" }
        renderer.showScene(scene)
    }

    override suspend fun clear() = operationMutex.withLock {
        val renderer = requireNotNull(host) { "Halo is not connected" }
        renderer.showScene(Json.parseToJsonElement("""{"scene":{"children":[]}}"""))
    }

    suspend fun disconnect() {
        connectJob?.cancel()
        runCatching { connectJob?.join() }
        operationMutex.withLock {
            disconnectLocked()
            _state.value = HaloConnectionState.Disconnected
        }
    }

    override suspend fun listen(
        maxDuration: Duration,
        gain: Int,
        aec: Boolean,
        voice: Boolean,
    ): ByteArray = requireConnection {
        val connected = checkNotNull(transport) { "Halo transport is not available" }
        val gainByte = (gain + 10).coerceIn(0, 20).toByte()
        val aecByte = if (aec) 1.toByte() else 0.toByte()
        val voiceByte = if (voice) 1.toByte() else 0.toByte()

        val session = HaloSession(connected)
        try {
            val pcm = session.collect(
                startCode = HaloProtocol.MICROPHONE_START,
                startPayload = byteArrayOf(gainByte, aecByte, voiceByte),
                stopCode = HaloProtocol.MICROPHONE_STOP,
                chunkCode = HaloProtocol.AUDIO_CHUNK,
                finalCode = HaloProtocol.AUDIO_FINAL,
                timeout = maxDuration + 2.seconds,
                maxBytes = minOf(MAX_MIC_BYTES, 2_000_000L),
                stopAfter = maxDuration,
            )
            if (pcm.isEmpty()) throw SensesError.Protocol("Microphone capture returned no audio")
            PcmToWav.fromPcm16(pcm)
        } catch (e: HaloTransportException) {
            throw SensesError.Disconnected(e.message ?: "Halo disconnected during listen")
        } catch (e: HaloLimitException) {
            throw SensesError.LimitExceeded(e.message ?: "audio limit exceeded")
        } catch (e: TimeoutCancellationException) {
            throw SensesError.Timeout("listen exceeded $maxDuration")
        }
    }

    override suspend fun capturePhoto(
        resolution: Int,
        qualityIndex: Int,
        pan: Int,
        raw: Boolean,
        maxBytes: Long,
    ): ByteArray = requireConnection {
        if (resolution != 640) {
            throw SensesError.Rejected("Camera resolution $resolution is not supported by this firmware profile; use 640")
        }
        if (pan != 0) {
            throw SensesError.Rejected("Pan is not supported by this firmware profile")
        }
        if (raw) {
            throw SensesError.Unavailable("Raw camera capture is not supported")
        }

        val connected = checkNotNull(transport) { "Halo transport is not available" }
        val halfRes = 320
        val panShifted = 140
        val quality = qualityIndex.coerceIn(0, 4)
        val payload = byteArrayOf(
            quality.toByte(),
            (halfRes shr 8).toByte(),
            halfRes.toByte(),
            (panShifted shr 8).toByte(),
            panShifted.toByte(),
            0,
        )

        val session = HaloSession(connected)
        try {
            val jpeg = session.collect(
                startCode = HaloProtocol.CAPTURE_PHOTO,
                startPayload = payload,
                chunkCode = HaloProtocol.PHOTO_JPEG,
                finalCode = HaloProtocol.PHOTO_FINAL,
                timeout = 30.seconds,
                maxBytes = maxBytes.coerceAtMost(MAX_PHOTO_BYTES),
            )
            if (jpeg.isEmpty()) throw SensesError.Protocol("Camera capture returned no image")
            jpeg
        } catch (e: HaloTransportException) {
            throw SensesError.Disconnected(e.message ?: "Halo disconnected during photo capture")
        } catch (e: HaloLimitException) {
            throw SensesError.LimitExceeded(e.message ?: "photo limit exceeded")
        } catch (e: TimeoutCancellationException) {
            throw SensesError.Timeout("photo capture exceeded 30 seconds")
        }
    }

    override suspend fun battery(): HaloBattery = requireConnection {
        val connected = checkNotNull(transport) { "Halo transport is not available" }
        val session = HaloSession(connected)
        try {
            val payload = session.requestResponse(
                requestCode = HaloProtocol.DEVICE_STATUS,
                requestPayload = byteArrayOf(),
                responseCode = HaloProtocol.DEVICE_STATUS,
                timeout = 5.seconds,
            )
            if (payload.size >= 4) {
                val level = payload[0].toInt() and 0xff
                val voltage = (payload[1].toInt() and 0xff) shl 8 or (payload[2].toInt() and 0xff)
                val charging = payload[3].toInt() != 0
                HaloBattery(level, voltage, charging)
            } else {
                throw SensesError.Protocol("Battery payload too short: ${payload.size} bytes")
            }
        } catch (e: HaloTransportException) {
            throw SensesError.Disconnected(e.message ?: "Halo disconnected during battery query")
        } catch (e: TimeoutCancellationException) {
            throw SensesError.Timeout("battery query timed out")
        }
    }

    override suspend fun waitForTap(timeout: Duration, kind: String?): HaloInputEvent =
        tapMutex.withLock {
            withTimeout(timeout) {
                inputEvents.first { event ->
                    kind == null || event.gesture == kind
                }
            }
        }

    override suspend fun speak(text: String, volume: Int) {
        val tts = initTts()
        val ready = ttsReady.await()
        check(ready == TextToSpeech.SUCCESS) { "Text-to-Speech initialization failed" }

        val tempFile = File.createTempFile("halo_tts", ".wav", appContext.cacheDir)
        val done = CompletableDeferred<Boolean>()
        try {
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { done.complete(true) }
                @Deprecated("UtteranceProgressListener.onError(String?) is deprecated", level = DeprecationLevel.HIDDEN)
                override fun onError(utteranceId: String?) { done.complete(false) }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { done.complete(false) }
            })

            val utteranceId = "halo-tts-${System.currentTimeMillis()}"
            val result = tts.synthesizeToFile(text, null, tempFile, utteranceId)
            require(result == TextToSpeech.SUCCESS) { "TTS synthesis request failed" }

            val ok = withTimeout(60.seconds) { done.await() }
            require(ok) { "TTS synthesis did not complete" }

            val audio = FileInputStream(tempFile).use { it.readBytes() }
            playAudio(
                AudioPlaybackRequest(
                    audio = audio,
                    format = AudioFormat(16000, 16, 1, "wav", "audio/wav"),
                    volume = volume,
                )
            )
        } finally {
            runCatching { tempFile.delete() }
        }
    }

    override suspend fun playAudio(request: AudioPlaybackRequest) {
        requireConnection {
            val connected = checkNotNull(transport) { "Halo transport is not available" }
            check(connected.supportsAudio) { "Halo speaker is not available on this device" }

            val startPayload = byteArrayOf(
                0,
                (16000 shr 8).toByte(),
                16000.toByte(),
                16,
                1,
                request.volume.coerceIn(0, 100).toByte(),
            )
            connected.sendMessage(HaloProtocol.SPEAKER_START, startPayload)

            try {
                when (request.format.encoding) {
                    "wav" -> {
                        ByteArrayInputStream(request.audio).use { input ->
                            WavReader.readPcm(input, 16000).collect { chunk ->
                                currentCoroutineContext().ensureActive()
                                connected.sendAudioFrame(chunk)
                                val sleepMs = chunk.size / 2 * 1000L / 16000
                                delay(sleepMs)
                            }
                        }
                    }
                    "pcm-s16le" -> {
                        val frameSize = 16000 * 2 * 10 / 1000 // 10 ms of 16-bit mono at 16 kHz
                        request.audio.asSequence().chunked(frameSize).forEachIndexed { index, bytes ->
                            currentCoroutineContext().ensureActive()
                            connected.sendAudioFrame(bytes.toByteArray())
                            delay(10)
                        }
                    }
                    else -> throw IllegalArgumentException("Unsupported playback encoding: ${request.format.encoding}")
                }
            } finally {
                runCatching { connected.sendMessage(HaloProtocol.SPEAKER_STOP, byteArrayOf()) }
            }
        }
    }

    private fun initTts(): TextToSpeech {
        textToSpeech?.let { return it }
        val tts = TextToSpeech(appContext, TextToSpeech.OnInitListener { status ->
            ttsReady.complete(status)
        })
        textToSpeech = tts
        return tts
    }

    private suspend inline fun <T> requireConnection(block: suspend () -> T): T {
        check(state.value is HaloConnectionState.Ready) { "Halo is not connected" }
        return block()
    }

    private suspend fun disconnectLocked() {
        connectionJob?.cancel()
        connectionJob = null
        inputJob?.cancel()
        inputJob = null
        host = null
        transport?.let { runCatching { it.disconnect() } }
        transport = null
    }

    fun close() {
        stopScan()
        scope.launch(Dispatchers.IO) { disconnect() }
    }

    /** Return whether the device-level Bluetooth adapter is enabled. */
    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    companion object {
    }
}
