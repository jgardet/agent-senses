package com.nyooran.agent.senses.simulator

import com.nyooran.agent.senses.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 3 P3-01: Chat sense endpoint.
 *
 * A reduced-capability sense endpoint backed by a chat window / conversation
 * interface. It advertises only the capabilities a chat UI can truthfully
 * provide:
 * - TextInput: user types text in the composer
 * - TextOutput: assistant text/Markdown in the conversation timeline
 * - InteractionInput: approval, selection, or action controls
 * - ImageInput: attachment picker (if enabled)
 *
 * It does NOT advertise microphone, camera, speaker, wearable tap, or
 * battery unless those are actually backed by a phone endpoint.
 *
 * The chat endpoint routes typed user text as input provenance and
 * assistant text through chat presentation output. It is not a parallel
 * application architecture — it uses the same SenseEndpoint contract
 * as Halo, phone, and simulator.
 */
class ChatEndpoint(
    private val config: ChatConfig = ChatConfig(),
) : SenseEndpoint {

    data class ChatConfig(
        val endpointId: EndpointId = EndpointId("chat-1"),
        val displayName: String = "Chat",
        val enableAttachments: Boolean = true,
        val connectionDelayMillis: Long = 0,
        /** Pending text input from the user (injected by the UI layer). */
        val pendingTextInput: String? = null,
        /** Pending interaction event (injected by the UI layer). */
        val pendingInteraction: InteractionEvent? = null,
        /** Image attachment fixture (when enableAttachments=true). */
        val imageFixture: ByteArray = ByteArray(0),
        val imageFormat: ImageFormat = ImageFormat("jpeg", "image/jpeg", 640, 640),
    )

    private val _state = MutableStateFlow(EndpointState.DISCONNECTED)
    val state: StateFlow<EndpointState> = _state.asStateFlow()

    private val _chatMessages = MutableSharedFlow<ChatMessage>(extraBufferCapacity = 64)
    val chatMessages: SharedFlow<ChatMessage> = _chatMessages.asSharedFlow()

    private var operationCounter = 0
    private var connected = false

    /** A chat message recorded by the endpoint. */
    data class ChatMessage(
        val role: String,  // "user" or "assistant"
        val text: String,
        val format: TextFormat = TextFormat.PLAIN,
        val timestamp: Long = System.currentTimeMillis(),
    )

    private val _recordedMessages = mutableListOf<ChatMessage>()
    val recordedMessages: List<ChatMessage> get() = _recordedMessages.toList()

    override val profile: SenseProfile = SenseProfile(
        endpointId = config.endpointId,
        backendName = "Chat",
        backendKind = BackendKind.CHAT,
        state = EndpointState.DISCONNECTED,
        capabilities = buildSet {
            add(SenseCapability.TextInput)
            add(SenseCapability.TextOutput)
            add(SenseCapability.InteractionInput)
            if (config.enableAttachments) add(SenseCapability.ImageInput)
        },
        limits = mapOf(
            SenseCapability.TextInput to SenseLimits(maxDurationMillis = 300_000),  // 5 min input timeout
            SenseCapability.TextOutput to SenseLimits(maxBytes = 65_536),
            SenseCapability.InteractionInput to SenseLimits(maxDurationMillis = 300_000),
        ),
        concurrency = ConcurrencyProfile(
            resourceDomains = mapOf(
                SenseCapability.TextInput to "ui",
                SenseCapability.TextOutput to "ui",
                SenseCapability.InteractionInput to "ui",
            ),
        ),
        displayName = config.displayName,
    )

    override suspend fun connect() {
        if (config.connectionDelayMillis > 0) delay(config.connectionDelayMillis)
        connected = true
        _state.value = EndpointState.READY
    }

    override suspend fun disconnect() {
        connected = false
        _state.value = EndpointState.DISCONNECTED
    }

    override suspend fun audioInput(request: AudioInputRequest): AudioInputResult {
        throw SensesError.Unavailable("Chat endpoint does not support audio input")
    }

    override suspend fun audioOutput(request: AudioOutputRequest): AudioOutputResult {
        throw SensesError.Unavailable("Chat endpoint does not support audio output")
    }

    override suspend fun imageInput(request: ImageInputRequest): ImageInputResult {
        if (!config.enableAttachments) {
            throw SensesError.Unavailable("Chat endpoint does not support image input (attachments disabled)")
        }
        val opId = "chat-op-${++operationCounter}"
        val startedAt = System.currentTimeMillis()
        return ImageInputResult(
            image = config.imageFixture,
            format = config.imageFormat,
            isRaw = false,
            provenance = Provenance(
                operationId = opId,
                endpointId = config.endpointId,
                backendName = "Chat",
                backendKind = BackendKind.CHAT,
                capability = SenseCapability.ImageInput,
                origin = ResultOrigin.ATTACHMENT,
                startedAt = startedAt,
                completedAt = System.currentTimeMillis(),
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
        throw SensesError.Unavailable("Chat endpoint does not support visual output (use TextOutput)")
    }

    override suspend fun textOutput(request: TextOutputRequest): TextOutputResult {
        val opId = "chat-op-${++operationCounter}"
        val startedAt = System.currentTimeMillis()
        val msg = ChatMessage(role = "assistant", text = request.content.text, format = request.content.format)
        _recordedMessages.add(msg)
        _chatMessages.emit(msg)
        return TextOutputResult(
            provenance = Provenance(
                operationId = opId,
                endpointId = config.endpointId,
                backendName = "Chat",
                backendKind = BackendKind.CHAT,
                capability = SenseCapability.TextOutput,
                origin = ResultOrigin.DISPLAY,
                startedAt = startedAt,
                completedAt = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun textInput(request: TextInputRequest): TextInputResult {
        val opId = "chat-op-${++operationCounter}"
        val startedAt = System.currentTimeMillis()
        val text = config.pendingTextInput ?: ""
        val msg = ChatMessage(role = "user", text = text)
        _recordedMessages.add(msg)
        _chatMessages.emit(msg)
        return TextInputResult(
            text = text,
            provenance = Provenance(
                operationId = opId,
                endpointId = config.endpointId,
                backendName = "Chat",
                backendKind = BackendKind.CHAT,
                capability = SenseCapability.TextInput,
                origin = ResultOrigin.KEYBOARD,
                startedAt = startedAt,
                completedAt = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun interactionInput(request: InteractionInputRequest): InteractionInputResult {
        val opId = "chat-op-${++operationCounter}"
        val startedAt = System.currentTimeMillis()
        val event = config.pendingInteraction ?: InteractionEvent.Approval(approved = true)
        return InteractionInputResult(
            event = event,
            provenance = Provenance(
                operationId = opId,
                endpointId = config.endpointId,
                backendName = "Chat",
                backendKind = BackendKind.CHAT,
                capability = SenseCapability.InteractionInput,
                origin = ResultOrigin.KEYBOARD,
                startedAt = startedAt,
                completedAt = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun statusInput(request: StatusInputRequest): StatusInputResult {
        throw SensesError.Unavailable("Chat endpoint does not expose device status")
    }
}
