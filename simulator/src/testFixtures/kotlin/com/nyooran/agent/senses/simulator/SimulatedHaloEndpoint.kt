package com.nyooran.agent.senses.simulator

import com.nyooran.agent.senses.*
import halo.engine.display.DisplayBuffer
import halo.engine.display.HrpRenderer
import halo.engine.transport.CapabilityStateMachine
import halo.engine.transport.DeviceEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
        val micChunkCount: Int = 10,
    )

    private val renderer = HrpRenderer()
    private val stateMachine = CapabilityStateMachine(
        halo.engine.transport.CapabilityConfig(
            batteryLevel = scenario.batteryLevel,
            batteryVoltage = scenario.batteryVoltage,
            batteryCharging = scenario.batteryCharging,
            micChunkBytes = scenario.audioFixture,
            micChunkCount = scenario.micChunkCount,
            photoData = scenario.imageFixture,
            photoChunkSize = 200,
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
            SenseCapability.ImageInput to SenseLimits(maxBytes = 65536),
            SenseCapability.AudioOutput to SenseLimits(maxBytes = 1_048_576, maxConcurrent = 1),
            SenseCapability.VisualOutput to SenseLimits(maxBytes = 4096),
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
        _recordedHrp.clear()
        _recordedAudio.clear()
        _recordedInteractions.clear()
        _state.value = EndpointState.DISCONNECTED
    }

    override suspend fun audioInput(request: AudioInputRequest): AudioInputResult {
        val opId = "sim-op-${++operationCounter}"
        val startedAt = System.currentTimeMillis()
        stateMachine.handleMessage(halo.engine.HaloProtocol.MICROPHONE_START, ByteArray(0))
        // Simulate chunk collection
        repeat(scenario.micChunkCount) { stateMachine.tick() }
        stateMachine.handleMessage(halo.engine.HaloProtocol.MICROPHONE_STOP, ByteArray(0))
        val events = stateMachine.drainEvents()
        val chunks = events.filterIsInstance<DeviceEvent.Message>()
            .filter { it.code == halo.engine.HaloProtocol.AUDIO_CHUNK }
        val pcm = chunks.flatMap { it.payload.toList() }.toByteArray()
        val completedAt = System.currentTimeMillis()
        return AudioInputResult(
            audio = pcm,
            format = scenario.audioFormat,
            durationMillis = scenario.audioDurationMillis,
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
                    durationMillis = scenario.audioDurationMillis,
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
        stateMachine.handleMessage(halo.engine.HaloProtocol.SPEAKER_START, ByteArray(0))
        stateMachine.handleMessage(halo.engine.HaloProtocol.SPEAKER_STOP, ByteArray(0))
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
        val opId = "sim-op-${++operationCounter}"
        val startedAt = System.currentTimeMillis()
        stateMachine.handleMessage(halo.engine.HaloProtocol.CAPTURE_PHOTO, ByteArray(0))
        stateMachine.tick()  // emit photo chunks
        val events = stateMachine.drainEvents()
        val photoChunks = events.filterIsInstance<DeviceEvent.Message>()
            .filter { it.code == halo.engine.HaloProtocol.PHOTO_JPEG }
        val image = if (photoChunks.isNotEmpty()) {
            photoChunks.flatMap { it.payload.toList() }.toByteArray()
        } else {
            scenario.imageFixture
        }
        val completedAt = System.currentTimeMillis()
        return ImageInputResult(
            image = image,
            format = scenario.imageFormat,
            isRaw = request.deviceOptions["raw"] as? Boolean ?: false,
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
                _recordedHrp.add(request.content.payload)
                stateMachine.handleMessage(halo.engine.HaloProtocol.HRP, request.content.payload)
                stateMachine.drainEvents()
            }
            VisualContent.VisualKind.IMAGE -> {
                // Render image as bitmap — for simulation, just record it
                _recordedHrp.add(request.content.payload)
            }
            VisualContent.VisualKind.TEXT -> {
                val text = String(request.content.payload, Charsets.UTF_8)
                stateMachine.handleMessage(0x11, text.toByteArray())  // PLAIN_TEXT
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
                startedAt = startedAt,
                completedAt = completedAt,
            ),
        )
    }

    override suspend fun textOutput(request: TextOutputRequest): TextOutputResult {
        val opId = "sim-op-${++operationCounter}"
        val startedAt = System.currentTimeMillis()
        stateMachine.handleMessage(0x11, request.content.text.toByteArray())  // PLAIN_TEXT
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
        // Default: return a single tap
        val event = InteractionEvent.Tap(TapGesture.SINGLE)
        _recordedInteractions.add(event)
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

    override suspend fun statusInput(request: StatusInputRequest): StatusInputResult {
        val opId = "sim-op-${++operationCounter}"
        val startedAt = System.currentTimeMillis()
        stateMachine.handleMessage(halo.engine.HaloProtocol.DEVICE_STATUS, ByteArray(0))
        val events = stateMachine.drainEvents()
        val statusMsg = events.filterIsInstance<DeviceEvent.Message>()
            .firstOrNull { it.code == halo.engine.HaloProtocol.DEVICE_STATUS }
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
    }
}
