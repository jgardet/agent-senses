package com.nyooran.agent.senses.simulator

import com.nyooran.agent.senses.*
import kotlinx.coroutines.delay

/**
 * Phase 3 P3-05: Generic deterministic fixture endpoint.
 *
 * A non-Halo sense endpoint for orchestration tests that do not depend
 * on Halo protocol behavior. Supports:
 * - Virtual time (via coroutine delay)
 * - Typed capability absence (unsupported capabilities throw Unavailable)
 * - Partial multimodal delivery (configurable per-capability failures)
 * - Stable fixture IDs
 *
 * Unlike [SimulatedHaloEndpoint], this endpoint has no engine-halo dependency
 * and can be used in pure core/orchestration tests.
 */
class FixtureEndpoint(
    private val config: FixtureConfig = FixtureConfig(),
) : SenseEndpoint {

    /**
     * Configuration for the fixture endpoint.
     */
    data class FixtureConfig(
        val endpointId: EndpointId = EndpointId("fixture-1"),
        val backendName: String = "Fixture",
        val backendKind: BackendKind = BackendKind.SIMULATOR,
        val displayName: String = "Test Fixture",
        val fixtureId: String = "fixture-default",
        val capabilities: Set<SenseCapability> = setOf(
            SenseCapability.AudioInput,
            SenseCapability.AudioOutput,
            SenseCapability.ImageInput,
            SenseCapability.VisualOutput,
            SenseCapability.TextOutput,
            SenseCapability.TextInput,
            SenseCapability.InteractionInput,
            SenseCapability.StatusInput,
        ),
        val limits: Map<SenseCapability, SenseLimits> = emptyMap(),
        val connectionDelayMillis: Long = 0,
        /** If non-null, the next operation of this capability fails with this error. */
        val failureForCapability: Map<SenseCapability, SenseFailure> = emptyMap(),
        /** Audio fixture bytes returned by AudioInput. */
        val audioFixture: ByteArray = ByteArray(160),
        val audioFormat: AudioFormat = AudioFormat(16000, 16, 1, "pcm", "audio/pcm"),
        val audioDurationMillis: Long = 10,
        /** Image fixture bytes returned by ImageInput. */
        val imageFixture: ByteArray = ByteArray(0),
        val imageFormat: ImageFormat = ImageFormat("jpeg", "image/jpeg", 640, 640),
        /** Status fixture returned by StatusInput. */
        val statusFixture: EndpointStatus = EndpointStatus(batteryLevel = 50),
        /** Interaction event returned by InteractionInput. */
        val interactionFixture: InteractionEvent = InteractionEvent.Tap(TapGesture.SINGLE),
        /** Text returned by TextInput. */
        val textFixture: String = "test input",
    )

    private var connected = false
    private var operationCounter = 0

    override val profile: SenseProfile = SenseProfile(
        endpointId = config.endpointId,
        backendName = config.backendName,
        backendKind = config.backendKind,
        state = EndpointState.DISCONNECTED,
        capabilities = config.capabilities,
        limits = config.limits,
        concurrency = ConcurrencyProfile(
            resourceDomains = mapOf(
                SenseCapability.AudioInput to "audio",
                SenseCapability.AudioOutput to "audio",
                SenseCapability.ImageInput to "camera",
                SenseCapability.VisualOutput to "display",
                SenseCapability.TextInput to "ui",
                SenseCapability.TextOutput to "ui",
                SenseCapability.InteractionInput to "ui",
            ).filterKeys { it in config.capabilities },
        ),
        displayName = config.displayName,
    )

    override suspend fun connect() {
        if (config.connectionDelayMillis > 0) delay(config.connectionDelayMillis)
        connected = true
    }

    override suspend fun disconnect() {
        connected = false
    }

    private fun checkSupported(capability: SenseCapability) {
        if (!profile.supports(capability)) {
            throw SensesError.Unavailable("$capability not supported by ${config.backendName}")
        }
        config.failureForCapability[capability]?.let { throw SensesError.Internal(it.message) }
    }

    private fun provenance(capability: SenseCapability, origin: ResultOrigin, opId: String) = Provenance(
        operationId = opId,
        endpointId = config.endpointId,
        backendName = config.backendName,
        backendKind = config.backendKind,
        capability = capability,
        origin = origin,
        fixtureId = config.fixtureId,
        startedAt = System.currentTimeMillis(),
        completedAt = System.currentTimeMillis(),
    )

    private fun nextOpId() = "fixture-op-${++operationCounter}"

    override suspend fun audioInput(request: AudioInputRequest): AudioInputResult {
        checkSupported(SenseCapability.AudioInput)
        return AudioInputResult(
            audio = config.audioFixture,
            format = config.audioFormat,
            durationMillis = config.audioDurationMillis,
            provenance = provenance(SenseCapability.AudioInput, ResultOrigin.MICROPHONE, nextOpId()).copy(
                mediaFormat = MediaFormat(
                    encoding = config.audioFormat.encoding,
                    mime = config.audioFormat.mime,
                    durationMillis = config.audioDurationMillis,
                    sampleRate = config.audioFormat.sampleRate,
                    channels = config.audioFormat.channels,
                ),
            ),
        )
    }

    override suspend fun audioOutput(request: AudioOutputRequest): AudioOutputResult {
        checkSupported(SenseCapability.AudioOutput)
        return AudioOutputResult(provenance(SenseCapability.AudioOutput, ResultOrigin.SPEAKER, nextOpId()))
    }

    override suspend fun imageInput(request: ImageInputRequest): ImageInputResult {
        checkSupported(SenseCapability.ImageInput)
        return ImageInputResult(
            image = config.imageFixture,
            format = config.imageFormat,
            isRaw = request.raw,
            provenance = provenance(SenseCapability.ImageInput, ResultOrigin.CAMERA, nextOpId()).copy(
                mediaFormat = MediaFormat(
                    encoding = config.imageFormat.encoding,
                    mime = config.imageFormat.mime,
                    width = config.imageFormat.width,
                    height = config.imageFormat.height,
                ),
            ),
        )
    }

    override suspend fun visualOutput(request: VisualOutputRequest): VisualOutputResult {
        checkSupported(SenseCapability.VisualOutput)
        return VisualOutputResult(provenance(SenseCapability.VisualOutput, ResultOrigin.DISPLAY, nextOpId()))
    }

    override suspend fun textOutput(request: TextOutputRequest): TextOutputResult {
        checkSupported(SenseCapability.TextOutput)
        return TextOutputResult(provenance(SenseCapability.TextOutput, ResultOrigin.DISPLAY, nextOpId()))
    }

    override suspend fun textInput(request: TextInputRequest): TextInputResult {
        checkSupported(SenseCapability.TextInput)
        return TextInputResult(
            text = config.textFixture,
            provenance = provenance(SenseCapability.TextInput, ResultOrigin.KEYBOARD, nextOpId()),
        )
    }

    override suspend fun interactionInput(request: InteractionInputRequest): InteractionInputResult {
        checkSupported(SenseCapability.InteractionInput)
        return InteractionInputResult(
            event = config.interactionFixture,
            provenance = provenance(SenseCapability.InteractionInput, ResultOrigin.SENSOR, nextOpId()),
        )
    }

    override suspend fun statusInput(request: StatusInputRequest): StatusInputResult {
        checkSupported(SenseCapability.StatusInput)
        return StatusInputResult(
            status = config.statusFixture,
            provenance = provenance(SenseCapability.StatusInput, ResultOrigin.SENSOR, nextOpId()),
        )
    }
}
