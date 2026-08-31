package com.nyooran.agent.senses

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Runs the shared endpoint contract suite against a fake endpoint
 * that implements all capabilities. This validates the contract suite
 * itself and serves as a reference for real endpoint implementations.
 */
class FakeEndpointContractTest : SenseEndpointContractTest() {

    override val fixture = object : ContractFixture {
        override val name = "FakeEndpoint"
        override val supportedCapabilities = setOf(
            SenseCapability.AudioInput,
            SenseCapability.AudioOutput,
            SenseCapability.ImageInput,
            SenseCapability.VisualOutput,
            SenseCapability.TextOutput,
            SenseCapability.TextInput,
            SenseCapability.InteractionInput,
            SenseCapability.StatusInput,
        )

        override fun createEndpoint() = FakeSenseEndpoint(
            endpointId = EndpointId("fake-1"),
            capabilities = supportedCapabilities,
        )
    }
}

/**
 * A simple in-memory [SenseEndpoint] for testing.
 * All operations succeed with minimal valid data.
 */
class FakeSenseEndpoint(
    endpointId: EndpointId,
    capabilities: Set<SenseCapability>,
    backendKind: BackendKind = BackendKind.SIMULATOR,
) : SenseEndpoint {

    private val allLimits = mapOf(
        SenseCapability.AudioInput to SenseLimits(maxDurationMillis = 60_000, maxBytes = 1_048_576),
        SenseCapability.ImageInput to SenseLimits(maxBytes = 65536),
        SenseCapability.AudioOutput to SenseLimits(maxConcurrent = 1),
    )

    private val allDomains = mapOf(
        SenseCapability.AudioInput to "audio",
        SenseCapability.AudioOutput to "audio",
        SenseCapability.ImageInput to "camera",
        SenseCapability.VisualOutput to "display",
        SenseCapability.TextInput to "ui",
        SenseCapability.TextOutput to "ui",
        SenseCapability.InteractionInput to "ui",
    )

    private val allConflicts = setOf(
        SenseCapability.AudioInput to SenseCapability.ImageInput,
    )

    override val profile = SenseProfile(
        endpointId = endpointId,
        backendName = "Fake",
        backendKind = backendKind,
        state = EndpointState.DISCONNECTED,
        capabilities = capabilities,
        limits = allLimits.filterKeys { it in capabilities },
        concurrency = ConcurrencyProfile(
            resourceDomains = allDomains.filterKeys { it in capabilities },
            explicitConflicts = allConflicts.filter { it.first in capabilities && it.second in capabilities }.toSet(),
        ),
    )

    private var connected = true  // Fake starts ready

    override suspend fun connect() { connected = true }

    override suspend fun disconnect() { connected = false }

    override suspend fun audioInput(request: AudioInputRequest): AudioInputResult {
        if (!profile.supports(SenseCapability.AudioInput))
            throw SensesError.Unavailable("AudioInput not supported")
        return AudioInputResult(
            audio = ByteArray(160),  // 5ms of 16kHz mono 16-bit
            format = AudioFormat(16000, 16, 1, "pcm", "audio/pcm"),
            durationMillis = 5,
            provenance = Provenance(
                operationId = "op-audio",
                endpointId = profile.endpointId,
                backendName = profile.backendName,
                backendKind = profile.backendKind,
                capability = SenseCapability.AudioInput,
                origin = ResultOrigin.MICROPHONE,
                mediaFormat = MediaFormat("pcm", "audio/pcm", durationMillis = 5, sampleRate = 16000, channels = 1),
                startedAt = 0,
                completedAt = 1,
            ),
        )
    }

    override suspend fun audioOutput(request: AudioOutputRequest): AudioOutputResult {
        if (!profile.supports(SenseCapability.AudioOutput))
            throw SensesError.Unavailable("AudioOutput not supported")
        return AudioOutputResult(
            provenance = Provenance(
                operationId = "op-audio-out",
                endpointId = profile.endpointId,
                backendName = profile.backendName,
                backendKind = profile.backendKind,
                capability = SenseCapability.AudioOutput,
                origin = ResultOrigin.SPEAKER,
                startedAt = 0,
                completedAt = 1,
            ),
        )
    }

    override suspend fun imageInput(request: ImageInputRequest): ImageInputResult {
        if (!profile.supports(SenseCapability.ImageInput))
            throw SensesError.Unavailable("ImageInput not supported")
        return ImageInputResult(
            image = ByteArray(100),
            format = ImageFormat("jpeg", "image/jpeg", 640, 640),
            isRaw = request.raw,
            provenance = Provenance(
                operationId = "op-image",
                endpointId = profile.endpointId,
                backendName = profile.backendName,
                backendKind = profile.backendKind,
                capability = SenseCapability.ImageInput,
                origin = ResultOrigin.CAMERA,
                mediaFormat = MediaFormat("jpeg", "image/jpeg", width = 640, height = 640),
                startedAt = 0,
                completedAt = 1,
            ),
        )
    }

    override suspend fun visualOutput(request: VisualOutputRequest): VisualOutputResult {
        if (!profile.supports(SenseCapability.VisualOutput))
            throw SensesError.Unavailable("VisualOutput not supported")
        return VisualOutputResult(
            provenance = Provenance(
                operationId = "op-visual",
                endpointId = profile.endpointId,
                backendName = profile.backendName,
                backendKind = profile.backendKind,
                capability = SenseCapability.VisualOutput,
                origin = ResultOrigin.DISPLAY,
                startedAt = 0,
                completedAt = 1,
            ),
        )
    }

    override suspend fun textOutput(request: TextOutputRequest): TextOutputResult {
        if (!profile.supports(SenseCapability.TextOutput))
            throw SensesError.Unavailable("TextOutput not supported")
        return TextOutputResult(
            provenance = Provenance(
                operationId = "op-text-out",
                endpointId = profile.endpointId,
                backendName = profile.backendName,
                backendKind = profile.backendKind,
                capability = SenseCapability.TextOutput,
                origin = ResultOrigin.DISPLAY,
                startedAt = 0,
                completedAt = 1,
            ),
        )
    }

    override suspend fun textInput(request: TextInputRequest): TextInputResult {
        if (!profile.supports(SenseCapability.TextInput))
            throw SensesError.Unavailable("TextInput not supported")
        return TextInputResult(
            text = "hello",
            provenance = Provenance(
                operationId = "op-text-in",
                endpointId = profile.endpointId,
                backendName = profile.backendName,
                backendKind = profile.backendKind,
                capability = SenseCapability.TextInput,
                origin = ResultOrigin.KEYBOARD,
                startedAt = 0,
                completedAt = 1,
            ),
        )
    }

    override suspend fun interactionInput(request: InteractionInputRequest): InteractionInputResult {
        if (!profile.supports(SenseCapability.InteractionInput))
            throw SensesError.Unavailable("InteractionInput not supported")
        return InteractionInputResult(
            event = InteractionEvent.Tap(TapGesture.SINGLE),
            provenance = Provenance(
                operationId = "op-interaction",
                endpointId = profile.endpointId,
                backendName = profile.backendName,
                backendKind = profile.backendKind,
                capability = SenseCapability.InteractionInput,
                origin = ResultOrigin.SENSOR,
                startedAt = 0,
                completedAt = 1,
            ),
        )
    }

    override suspend fun statusInput(request: StatusInputRequest): StatusInputResult {
        if (!profile.supports(SenseCapability.StatusInput))
            throw SensesError.Unavailable("StatusInput not supported")
        return StatusInputResult(
            status = EndpointStatus(batteryLevel = 75, batteryCharging = false),
            provenance = Provenance(
                operationId = "op-status",
                endpointId = profile.endpointId,
                backendName = profile.backendName,
                backendKind = profile.backendKind,
                capability = SenseCapability.StatusInput,
                origin = ResultOrigin.SENSOR,
                startedAt = 0,
                completedAt = 1,
            ),
        )
    }
}
