package com.nyooran.agent.senses.simulator

import com.nyooran.agent.senses.*
import com.nyooran.agent.senses.audio.PcmToWav
import halo.engine.HaloProtocol
import halo.engine.HsdHrpCompiler
import halo.engine.StubSpritePacker
import halo.engine.display.HrpFailure
import halo.engine.display.HrpRenderer
import halo.engine.transport.CapabilityConfig
import halo.engine.transport.CapabilityStateMachine
import halo.engine.transport.DeviceEvent
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlin.math.ceil

/**
 * Phase 3 P3-04: Simulated Halo sense endpoint.
 *
 * Wraps the engine-halo virtual runtime (CapabilityStateMachine + HrpRenderer)
 * as a [SenseEndpoint] implementing the Phase 2 contract. This is the
 * deterministic test fixture for Halo-specific behavior — it produces
 * realistic device responses without BLE hardware.
 *
 * Key properties:
 * - Deterministic: same scenario → same results, no random behavior.
 * - Fixture-identified: provenance always includes `backendKind=SIMULATOR`
 *   and a stable `fixtureId`.
 * - Records all operations: framebuffer snapshots, speaker PCM, interaction
 *   history, and operation state are available for test assertions.
 * - Repeatable connect/disconnect/reconnect.
 * - Clears retained media and subscribers on close/replacement.
 *
 * Acceptance: Simulated success cannot be mistaken for physical, phone,
 * chat, or model output.
 */
class SimulatedHaloEndpoint(
    private val scenario: HaloScenario = HaloScenario(),
    private val endpointId: EndpointId = EndpointId("sim-halo-1"),
) : SenseEndpoint {

    /** Scenario configuration for the simulated Halo. */
    data class HaloScenario(
        val displayName: String = "Simulated Halo",
        val fixtureId: String = "halo-default",
        val batteryLevel: Int = 80,
        val batteryVoltage: Int = 4100,
        val batteryCharging: Boolean = false,
        val audioFixture: ByteArray = ByteArray(320) { 0 },  // 10ms of 16kHz mono
        val audioDurationMillis: Long = 100,
        val audioFormat: AudioFormat = AudioFormat(16000, 16, 1, "wav", "audio/wav"),
        val imageFixture: ByteArray = ByteArray(0),
        val imageFormat: ImageFormat = ImageFormat("jpeg", "image/jpeg", 640, 640),
        val connectionDelayMillis: Long = 0,
        val micChunkCount: Int = 1000,
        val maxHrpBytes: Int = 4096,
        val maxHsdBytes: Int = 65_536,
    ) {
        companion object {
            // Safety cap to keep simulated audio collection bounded.
            const val MAX_MIC_CHUNKS: Int = 10_000
        }
    }

    private val renderer = HrpRenderer()
    private val json = Json { ignoreUnknownKeys = true }

    private val stateMachine = CapabilityStateMachine(
        CapabilityConfig(
            batteryLevel = scenario.batteryLevel,
            batteryVoltage = scenario.batteryVoltage,
            batteryCharging = scenario.batteryCharging,
        )
    )

    private val _state = MutableStateFlow(EndpointState.DISCONNECTED)
    val state: StateFlow<EndpointState> = _state.asStateFlow()

    // Recording state
    private val _recordedHrp = mutableListOf<ByteArray>()
    val recordedHrp: List<ByteArray> get() = _recordedHrp.toList()

    private val _recordedAudio = mutableListOf<ByteArray>()
    val recordedAudio: List<ByteArray> get() = _recordedAudio.toList()

    private val _recordedInteractions = mutableListOf<InteractionEvent>()
    val recordedInteractions: List<InteractionEvent> get() = _recordedInteractions.toList()

    private val interactionQueue = ConcurrentLinkedQueue<InteractionEvent>()

    private var operationCounter = 0

    override val profile: SenseProfile = SenseProfile(
        endpointId = endpointId,
        backendName = "Simulated Halo",
        backendKind = BackendKind.SIMULATOR,
        state = EndpointState.DISCONNECTED,
        capabilities = setOf(
            SenseCapability.AudioInput,
            SenseCapability.AudioOutput,
            SenseCapability.ImageInput,
            SenseCapability.VisualOutput,
            SenseCapability.InteractionInput,
            SenseCapability.StatusInput,
        ),
        limits = mapOf(
            SenseCapability.AudioInput to SenseLimits(maxDurationMillis = 60_000, maxBytes = 1_048_576),
            SenseCapability.ImageInput to SenseLimits(maxBytes = 65_536),
            SenseCapability.AudioOutput to SenseLimits(maxBytes = 1_048_576, maxConcurrent = 1),
            SenseCapability.VisualOutput to SenseLimits(maxBytes = scenario.maxHsdBytes.toLong()),
        ),
        concurrency = ConcurrencyProfile(
            resourceDomains = mapOf(
                SenseCapability.AudioInput to "audio",
                SenseCapability.AudioOutput to "audio",
                SenseCapability.ImageInput to "camera",
                SenseCapability.VisualOutput to "display",
            ),
            explicitConflicts = setOf(
                SenseCapability.AudioInput to SenseCapability.ImageInput,
            ),
        ),
        displayName = scenario.displayName,
    )

    override suspend fun connect() {
        if (scenario.connectionDelayMillis > 0) delay(scenario.connectionDelayMillis)
        stateMachine.boot()
        stateMachine.drainEvents()  // consume boot events
        _state.value = EndpointState.READY
    }

    override suspend fun disconnect() {
        stateMachine.reset()
        renderer.framebuffer().clear(0x000000)
        _recordedHrp.clear()
        _recordedAudio.clear()
        _recordedInteractions.clear()
        interactionQueue.clear()
        _state.value = EndpointState.DISCONNECTED
    }

    override suspend fun audioInput(request: AudioInputRequest): AudioInputResult {
        val opId = "sim-op-${++operationCounter}"
        val startedAt = System.currentTimeMillis()

        val chunkSize = scenario.audioFixture.size
        val bytesPerSample = scenario.audioFormat.bitDepth / 8 * scenario.audioFormat.channels
        val bytesPerSecond = scenario.audioFormat.sampleRate * bytesPerSample
        val chunkDurationMillis = if (bytesPerSecond > 0) {
            chunkSize * 1000L / bytesPerSecond
        } else {
            10L
        }

        val maxByDuration = if (chunkDurationMillis > 0) request.maxDurationMillis / chunkDurationMillis else 0L
        val maxByBytes = if (chunkSize > 0) request.maxBytes.toLong() / chunkSize else 0L
        val targetChunks = minOf(
            maxByDuration,
            maxByBytes,
            scenario.micChunkCount.toLong(),
            HaloScenario.MAX_MIC_CHUNKS.toLong(),
        ).toInt().coerceAtLeast(0)

        val audioConfig = CapabilityConfig(
            batteryLevel = scenario.batteryLevel,
            batteryVoltage = scenario.batteryVoltage,
            batteryCharging = scenario.batteryCharging,
            micChunkBytes = scenario.audioFixture,
            micChunkCount = targetChunks,
        )
        val audioStateMachine = CapabilityStateMachine(audioConfig)

        val startPayload = byteArrayOf(
            (request.gain + 10).toByte(),
            if (request.aec) 1.toByte() else 0.toByte(),
            if (request.voice) 1.toByte() else 0.toByte(),
        )
        audioStateMachine.handleMessage(HaloProtocol.MICROPHONE_START, startPayload)

        var safety = 0
        while (audioStateMachine.isMicStreaming() && safety < HaloScenario.MAX_MIC_CHUNKS) {
            audioStateMachine.tick()
            safety++
        }
        audioStateMachine.handleMessage(HaloProtocol.MICROPHONE_STOP, byteArrayOf())

        val events = audioStateMachine.drainEvents()
        val chunks = events.filterIsInstance<DeviceEvent.Message>()
            .filter { it.code == HaloProtocol.AUDIO_CHUNK }
        val pcm = chunks.flatMap { it.payload.toList() }.toByteArray()

        val wav = if (pcm.isEmpty()) {
            PcmToWav.fromPcm16(ByteArray(0), scenario.audioFormat.sampleRate, scenario.audioFormat.channels, scenario.audioFormat.bitDepth)
        } else {
            PcmToWav.fromPcm16(pcm, scenario.audioFormat.sampleRate, scenario.audioFormat.channels, scenario.audioFormat.bitDepth)
        }

        val durationMillis = if (bytesPerSecond > 0) {
            pcm.size * 1000L / bytesPerSecond
        } else {
            0L
        }

        val completedAt = System.currentTimeMillis()
        return AudioInputResult(
            audio = wav,
            format = scenario.audioFormat,
            durationMillis = durationMillis,
            provenance = Provenance(
                operationId = opId,
                endpointId = endpointId,
                backendName = profile.backendName,
                backendKind = BackendKind.SIMULATOR,
                capability = SenseCapability.AudioInput,
                origin = ResultOrigin.MICROPHONE,
                fixtureId = scenario.fixtureId,
                mediaFormat = MediaFormat(
                    encoding = scenario.audioFormat.encoding,
                    mime = scenario.audioFormat.mime,
                    durationMillis = durationMillis,
                    sampleRate = scenario.audioFormat.sampleRate,
                    channels = scenario.audioFormat.channels,
                ),
                startedAt = startedAt,
                completedAt = completedAt,
            ),
        )
    }

    override suspend fun audioOutput(request: AudioOutputRequest): AudioOutputResult {
        val opId = "sim-op-${++operationCounter}"
        val startedAt = System.currentTimeMillis()
        _recordedAudio.add(request.audio)

        val startPayload = byteArrayOf(
            0, // PCM encoder
            (request.format.sampleRate shr 8).toByte(),
            request.format.sampleRate.toByte(),
            request.format.bitDepth.toByte(),
            request.format.channels.toByte(),
            request.volume.toByte(),
        )
        stateMachine.handleMessage(HaloProtocol.SPEAKER_START, startPayload)
        stateMachine.drainEvents()
        stateMachine.handleMessage(HaloProtocol.SPEAKER_STOP, byteArrayOf())
        stateMachine.drainEvents()

        val completedAt = System.currentTimeMillis()
        return AudioOutputResult(
            provenance = Provenance(
                operationId = opId,
                endpointId = endpointId,
                backendName = profile.backendName,
                backendKind = BackendKind.SIMULATOR,
                capability = SenseCapability.AudioOutput,
                origin = ResultOrigin.SPEAKER,
                fixtureId = scenario.fixtureId,
                mediaFormat = MediaFormat(
                    encoding = request.format.encoding,
                    mime = request.format.mime,
                    sampleRate = request.format.sampleRate,
                    channels = request.format.channels,
                ),
                startedAt = startedAt,
                completedAt = completedAt,
            ),
        )
    }

    override suspend fun imageInput(request: ImageInputRequest): ImageInputResult {
        if (request.resolution != 640) {
            throw SensesError.Rejected("Halo camera is fixed at 640x640, requested ${request.resolution}")
        }
        if (request.deviceOptions["raw"] as? Boolean == true) {
            throw SensesError.Rejected("Halo camera does not support raw capture")
        }
        if ("pan" in request.deviceOptions) {
            throw SensesError.Rejected("Halo camera does not support pan")
        }

        val opId = "sim-op-${++operationCounter}"
        val startedAt = System.currentTimeMillis()

        val qualityIndex = (request.deviceOptions["qualityIndex"] as? Int
            ?: request.deviceOptions["quality_index"] as? Int
            ?: 4).coerceIn(0, 4)

        val limitedImage = if (scenario.imageFixture.size <= request.maxBytes) {
            scenario.imageFixture
        } else {
            scenario.imageFixture.copyOfRange(0, request.maxBytes)
        }

        val photoConfig = CapabilityConfig(
            batteryLevel = scenario.batteryLevel,
            batteryVoltage = scenario.batteryVoltage,
            batteryCharging = scenario.batteryCharging,
            photoData = limitedImage,
            photoChunkSize = 512,
        )
        val photoMachine = CapabilityStateMachine(photoConfig)

        val capturePayload = byteArrayOf(
            qualityIndex.toByte(),
            (320 shr 8).toByte(),
            320.toByte(),
            (140 shr 8).toByte(),
            140.toByte(),
            0,
        )
        photoMachine.handleMessage(HaloProtocol.CAPTURE_PHOTO, capturePayload)

        var safety = 0
        while (photoMachine.isPhotoPending() && safety < 10_000) {
            photoMachine.tick()
            safety++
        }

        val events = photoMachine.drainEvents()
        val photoChunks = events.filterIsInstance<DeviceEvent.Message>()
            .filter { it.code == HaloProtocol.PHOTO_JPEG }
        val image = if (photoChunks.isNotEmpty()) {
            photoChunks.flatMap { it.payload.toList() }.toByteArray()
        } else {
            scenario.imageFixture
        }

        val completedAt = System.currentTimeMillis()
        return ImageInputResult(
            image = image,
            format = scenario.imageFormat,
            isRaw = false,
            provenance = Provenance(
                operationId = opId,
                endpointId = endpointId,
                backendName = profile.backendName,
                backendKind = BackendKind.SIMULATOR,
                capability = SenseCapability.ImageInput,
                origin = ResultOrigin.CAMERA,
                fixtureId = scenario.fixtureId,
                mediaFormat = MediaFormat(
                    encoding = scenario.imageFormat.encoding,
                    mime = scenario.imageFormat.mime,
                    width = scenario.imageFormat.width,
                    height = scenario.imageFormat.height,
                ),
                startedAt = startedAt,
                completedAt = completedAt,
            ),
        )
    }

    override suspend fun visualOutput(request: VisualOutputRequest): VisualOutputResult {
        val opId = "sim-op-${++operationCounter}"
        val startedAt = System.currentTimeMillis()

        when (request.content.kind) {
            VisualContent.VisualKind.DEVICE_NATIVE -> {
                val hrpPayload = compileVisualPayload(request.content)
                _recordedHrp.add(hrpPayload)
                try {
                    renderer.render(hrpPayload)
                } catch (e: HrpFailure) {
                    throw SensesError.Protocol("HRP render failed: ${e.message}")
                }
                stateMachine.handleMessage(HaloProtocol.HRP, hrpPayload)
                stateMachine.drainEvents()
            }
            VisualContent.VisualKind.IMAGE -> {
                _recordedHrp.add(request.content.payload)
            }
            VisualContent.VisualKind.TEXT -> {
                stateMachine.handleMessage(0x11, request.content.payload)
                stateMachine.drainEvents()
            }
        }

        val completedAt = System.currentTimeMillis()
        return VisualOutputResult(
            provenance = Provenance(
                operationId = opId,
                endpointId = endpointId,
                backendName = profile.backendName,
                backendKind = BackendKind.SIMULATOR,
                capability = SenseCapability.VisualOutput,
                origin = ResultOrigin.DISPLAY,
                fixtureId = scenario.fixtureId,
                transformations = if (request.content.format == "hsd")
                    listOf(Transformation.PRESENTATION_COMPILATION) else emptyList(),
                startedAt = startedAt,
                completedAt = completedAt,
            ),
        )
    }

    private fun compileVisualPayload(content: VisualContent): ByteArray {
        return when (content.format ?: "hrp") {
            "hsd" -> {
                if (content.payload.size > scenario.maxHsdBytes) {
                    throw SensesError.LimitExceeded("HSD payload ${content.payload.size} exceeds limit ${scenario.maxHsdBytes}")
                }
                val scene = try {
                    json.parseToJsonElement(content.payload.toString(Charsets.UTF_8))
                } catch (e: Exception) {
                    throw SensesError.Rejected("Invalid HSD JSON: ${e.message}")
                }
                try {
                    HsdHrpCompiler(StubSpritePacker()).compile(scene)
                } catch (e: IllegalArgumentException) {
                    throw SensesError.Rejected("HSD compilation failed: ${e.message}")
                }
            }
            "hrp" -> {
                if (content.payload.size > scenario.maxHrpBytes) {
                    throw SensesError.LimitExceeded("HRP payload ${content.payload.size} exceeds limit ${scenario.maxHrpBytes}")
                }
                content.payload
            }
            else -> throw SensesError.Rejected("Unsupported device_native format: ${content.format}")
        }
    }

    override suspend fun textOutput(request: TextOutputRequest): TextOutputResult {
        val opId = "sim-op-${++operationCounter}"
        val startedAt = System.currentTimeMillis()
        stateMachine.handleMessage(0x11, request.content.text.toByteArray())
        stateMachine.drainEvents()
        val completedAt = System.currentTimeMillis()
        return TextOutputResult(
            provenance = Provenance(
                operationId = opId,
                endpointId = endpointId,
                backendName = profile.backendName,
                backendKind = BackendKind.SIMULATOR,
                capability = SenseCapability.TextOutput,
                origin = ResultOrigin.DISPLAY,
                fixtureId = scenario.fixtureId,
                startedAt = startedAt,
                completedAt = completedAt,
            ),
        )
    }

    override suspend fun textInput(request: TextInputRequest): TextInputResult {
        throw SensesError.Unavailable("Simulated Halo does not support text input (no keyboard)")
    }

    override suspend fun interactionInput(request: InteractionInputRequest): InteractionInputResult {
        val opId = "sim-op-${++operationCounter}"
        val startedAt = System.currentTimeMillis()

        val event = pollInteraction(request) ?: defaultInteraction(request.acceptedGestures)
        if (!eventFromQueue) {
            _recordedInteractions.add(event)
        }

        val completedAt = System.currentTimeMillis()
        return InteractionInputResult(
            event = event,
            provenance = Provenance(
                operationId = opId,
                endpointId = endpointId,
                backendName = profile.backendName,
                backendKind = BackendKind.SIMULATOR,
                capability = SenseCapability.InteractionInput,
                origin = ResultOrigin.SENSOR,
                fixtureId = scenario.fixtureId,
                startedAt = startedAt,
                completedAt = completedAt,
            ),
        )
    }

    private var eventFromQueue = false

    private fun pollInteraction(request: InteractionInputRequest): InteractionEvent? {
        val iterator = interactionQueue.iterator()
        while (iterator.hasNext()) {
            val event = iterator.next()
            if (request.acceptedGestures.isEmpty() || InteractionFilter(event, request)) {
                iterator.remove()
                eventFromQueue = true
                return event
            }
        }
        eventFromQueue = false
        return null
    }

    private fun defaultInteraction(accepted: Set<InteractionType>): InteractionEvent {
        val type = GESTURE_PRIORITY.firstOrNull { it in accepted }
            ?: InteractionType.TAP_SINGLE
        return when (type) {
            InteractionType.TAP_SINGLE -> InteractionEvent.Tap(TapGesture.SINGLE)
            InteractionType.TAP_DOUBLE -> InteractionEvent.Tap(TapGesture.DOUBLE)
            InteractionType.TAP_TRIPLE -> InteractionEvent.Tap(TapGesture.TRIPLE)
            InteractionType.BUTTON_SINGLE -> InteractionEvent.Button(ButtonGesture.SINGLE)
            InteractionType.BUTTON_DOUBLE -> InteractionEvent.Button(ButtonGesture.DOUBLE)
            InteractionType.BUTTON_LONG -> InteractionEvent.Button(ButtonGesture.LONG)
            InteractionType.APPROVAL -> InteractionEvent.Approval(approved = true)
            InteractionType.SELECTION -> InteractionEvent.Selection(selectedIndex = 0)
            InteractionType.TEXT_ENTRY -> InteractionEvent.TextEntry("")
        }
    }

    override suspend fun statusInput(request: StatusInputRequest): StatusInputResult {
        val opId = "sim-op-${++operationCounter}"
        val startedAt = System.currentTimeMillis()
        stateMachine.handleMessage(HaloProtocol.DEVICE_STATUS, byteArrayOf())
        val events = stateMachine.drainEvents()
        val statusMsg = events.filterIsInstance<DeviceEvent.Message>()
            .firstOrNull { it.code == HaloProtocol.DEVICE_STATUS }
        val completedAt = System.currentTimeMillis()
        val status = if (statusMsg != null && statusMsg.payload.size >= 4) {
            EndpointStatus(
                batteryLevel = statusMsg.payload[0].toInt() and 0xFF,
                batteryVoltage = ((statusMsg.payload[1].toInt() and 0xFF) shl 8) or (statusMsg.payload[2].toInt() and 0xFF),
                batteryCharging = (statusMsg.payload[3].toInt() and 0xFF) != 0,
            )
        } else {
            EndpointStatus(
                batteryLevel = scenario.batteryLevel,
                batteryVoltage = scenario.batteryVoltage,
                batteryCharging = scenario.batteryCharging,
            )
        }
        return StatusInputResult(
            status = status,
            provenance = Provenance(
                operationId = opId,
                endpointId = endpointId,
                backendName = profile.backendName,
                backendKind = BackendKind.SIMULATOR,
                capability = SenseCapability.StatusInput,
                origin = ResultOrigin.SENSOR,
                fixtureId = scenario.fixtureId,
                startedAt = startedAt,
                completedAt = completedAt,
            ),
        )
    }

    /** Get the current framebuffer snapshot (for test assertions). */
    fun framebufferSnapshot(): IntArray = renderer.snapshot()

    /** Inject a tap event (for testing interaction handling). */
    fun injectTap(gesture: TapGesture) {
        val event = InteractionEvent.Tap(gesture)
        _recordedInteractions.add(event)
        val code = when (gesture) {
            TapGesture.SINGLE -> 1
            TapGesture.DOUBLE -> 2
            TapGesture.TRIPLE -> 3
        }
        stateMachine.tapEvent(code)
        interactionQueue.add(event)
    }

    /** Inject a button event (for testing interaction handling). */
    fun injectButton(gesture: ButtonGesture) {
        val event = InteractionEvent.Button(gesture)
        _recordedInteractions.add(event)
        val code = when (gesture) {
            ButtonGesture.SINGLE -> 1
            ButtonGesture.DOUBLE -> 2
            ButtonGesture.LONG -> 3
        }
        stateMachine.buttonEvent(code)
        interactionQueue.add(event)
    }

    companion object {
        private val GESTURE_PRIORITY = listOf(
            InteractionType.TAP_SINGLE,
            InteractionType.TAP_DOUBLE,
            InteractionType.TAP_TRIPLE,
            InteractionType.BUTTON_SINGLE,
            InteractionType.BUTTON_DOUBLE,
            InteractionType.BUTTON_LONG,
            InteractionType.APPROVAL,
            InteractionType.SELECTION,
            InteractionType.TEXT_ENTRY,
        )
    }
}
