package com.nyooran.agent.senses

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A chat-window endpoint. It provides [SenseCapability.TextInput],
 * [SenseCapability.TextOutput], [SenseCapability.InteractionInput], and
 * optionally [SenseCapability.ImageInput] (attachments) for the primary
 * agent conversation surface.
 *
 * The application delivers user text through [postUserMessage], which resumes
 * any pending [textInput] or [interactionInput] request. Assistant text is
 * emitted through [assistantMessages] and recorded in [recordedMessages] when
 * [textOutput] is called.
 *
 * For tests and simulators, [ChatConfig.pendingTextInput],
 * [ChatConfig.pendingInteraction], and [ChatConfig.imageFixture] supply
 * deterministic input.
 */
class ChatEndpoint(
    private val config: ChatConfig = ChatConfig(),
) : SenseEndpoint {

    /**
     * Configuration for [ChatEndpoint].
     *
     * The fixture fields are used by tests and simulators; the production app
     * uses [postUserMessage] to drive input and [assistantMessages] to observe
     * output.
     */
    data class ChatConfig(
        val endpointId: EndpointId = EndpointId("chat-1"),
        val backendName: String = "chat",
        val displayName: String = "Chat",
        val enableAttachments: Boolean = true,
        val connectionDelayMillis: Long = 0,
        /** Pending text input from the user (injected by the UI or test layer). */
        val pendingTextInput: String? = null,
        /** Pending interaction event (injected by the UI or test layer). */
        val pendingInteraction: InteractionEvent? = null,
        /** Image attachment fixture (when enableAttachments=true). */
        val imageFixture: ByteArray = ByteArray(0),
        val imageFormat: ImageFormat = ImageFormat("jpeg", "image/jpeg", 640, 640),
    )

    /**
     * A message recorded by the chat endpoint.
     */
    data class ChatMessage(
        val role: String,  // "user" or "assistant"
        val text: String,
        val format: TextFormat = TextFormat.PLAIN,
        val timestamp: Long = System.currentTimeMillis(),
    )

    constructor(
        endpointId: EndpointId = EndpointId("chat"),
        backendName: String = "chat",
        displayName: String = "Chat",
    ) : this(
        ChatConfig(
            endpointId = endpointId,
            backendName = backendName,
            displayName = displayName,
        )
    )

    private val _state = MutableStateFlow(EndpointState.DISCONNECTED)
    val state: StateFlow<EndpointState> = _state.asStateFlow()

    private val _assistantMessages = MutableSharedFlow<ChatMessage>(extraBufferCapacity = 64)
    val assistantMessages: SharedFlow<ChatMessage> = _assistantMessages.asSharedFlow()

    private val _recordedMessages = mutableListOf<ChatMessage>()
    val recordedMessages: List<ChatMessage> get() = _recordedMessages.toList()

    private var connected = false
    private var operationCounter = 0

    private val inputLock = Mutex()
    private var pendingInput: CompletableDeferred<String>? = null

    private val capabilities = buildSet {
        add(SenseCapability.TextInput)
        add(SenseCapability.TextOutput)
        add(SenseCapability.InteractionInput)
        if (config.enableAttachments) add(SenseCapability.ImageInput)
    }

    private val profileValue = SenseProfile(
        endpointId = config.endpointId,
        backendName = config.backendName,
        backendKind = BackendKind.CHAT,
        state = _state.value,
        capabilities = capabilities,
        limits = mapOf(
            SenseCapability.TextInput to SenseLimits(maxDurationMillis = 300_000),
            SenseCapability.TextOutput to SenseLimits(maxBytes = 65_536),
            SenseCapability.InteractionInput to SenseLimits(maxDurationMillis = 300_000),
        ),
        concurrency = ConcurrencyProfile(
            resourceDomains = mapOf(
                SenseCapability.TextInput to "ui",
                SenseCapability.TextOutput to "ui",
                SenseCapability.InteractionInput to "ui",
                SenseCapability.ImageInput to "ui",
            ),
            explicitConflicts = setOf(
                SenseCapability.TextInput to SenseCapability.InteractionInput,
            ),
        ),
        displayName = config.displayName,
    )

    override val profile: SenseProfile get() = profileValue.copy(state = _state.value)

    override suspend fun connect() {
        if (config.connectionDelayMillis > 0) delay(config.connectionDelayMillis)
        connected = true
        _state.value = EndpointState.READY
    }

    override suspend fun disconnect() {
        connected = false
        _state.value = EndpointState.DISCONNECTED
        inputLock.withLock {
            pendingInput?.cancel()
            pendingInput = null
        }
    }

    /**
     * Deliver a user message from the chat UI. Resumes any pending [textInput]
     * or [interactionInput] request. If no request is pending, the message is
     * dropped.
     */
    suspend fun postUserMessage(text: String): Boolean = inputLock.withLock {
        val deferred = pendingInput
        if (deferred != null && !deferred.isCompleted) {
            deferred.complete(text)
            pendingInput = null
            true
        } else {
            false
        }
    }

    /**
     * Cancel any pending text input (e.g. when the session ends).
     */
    fun cancelInput() {
        inputLock.tryLock()
        try {
            pendingInput?.cancel()
            pendingInput = null
        } finally {
            inputLock.unlock()
        }
    }

    override suspend fun audioInput(request: AudioInputRequest): AudioInputResult =
        throw SensesError.Unavailable("Chat does not support audio input")

    override suspend fun audioOutput(request: AudioOutputRequest): AudioOutputResult =
        throw SensesError.Unavailable("Chat does not support audio output")

    override suspend fun imageInput(request: ImageInputRequest): ImageInputResult {
        if (!config.enableAttachments) {
            throw SensesError.Unavailable("Chat does not support image input (attachments disabled)")
        }
        val opId = nextOpId()
        val startedAt = System.currentTimeMillis()
        return ImageInputResult(
            image = config.imageFixture,
            format = config.imageFormat,
            isRaw = false,
            provenance = Provenance(
                operationId = opId,
                endpointId = config.endpointId,
                backendName = config.backendName,
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

    override suspend fun visualOutput(request: VisualOutputRequest): VisualOutputResult =
        throw SensesError.Unavailable("Chat does not support visual output")

    override suspend fun textOutput(request: TextOutputRequest): TextOutputResult {
        val opId = nextOpId()
        val startedAt = System.currentTimeMillis()
        val msg = ChatMessage(role = "assistant", text = request.content.text, format = request.content.format)
        _recordedMessages.add(msg)
        _assistantMessages.emit(msg)
        val completedAt = System.currentTimeMillis()
        val provenance = Provenance(
            operationId = opId,
            endpointId = config.endpointId,
            backendName = config.backendName,
            backendKind = profileValue.backendKind,
            capability = SenseCapability.TextOutput,
            origin = ResultOrigin.DISPLAY,
            startedAt = startedAt,
            completedAt = completedAt,
        )
        return TextOutputResult(provenance = provenance)
    }

    override suspend fun textInput(request: TextInputRequest): TextInputResult {
        val opId = nextOpId()
        val startedAt = System.currentTimeMillis()

        val text = if (config.pendingTextInput != null) {
            config.pendingTextInput
        } else {
            val deferred = inputLock.withLock {
                if (pendingInput != null) {
                    throw SensesError.Rejected("another input request is already pending")
                }
                CompletableDeferred<String>().also { pendingInput = it }
            }
            try {
                withTimeoutOrNull(request.timeoutMillis) { deferred.await() }
                    ?: throw SensesError.Timeout("text input timed out")
            } catch (e: Throwable) {
                inputLock.withLock { pendingInput = null }
                throw e
            }
        }

        val msg = ChatMessage(role = "user", text = text, timestamp = startedAt)
        _recordedMessages.add(msg)
        _assistantMessages.emit(msg)

        val completedAt = System.currentTimeMillis()
        val provenance = Provenance(
            operationId = opId,
            endpointId = config.endpointId,
            backendName = config.backendName,
            backendKind = profileValue.backendKind,
            capability = SenseCapability.TextInput,
            origin = ResultOrigin.KEYBOARD,
            startedAt = startedAt,
            completedAt = completedAt,
        )
        return TextInputResult(text = text, provenance = provenance)
    }

    override suspend fun interactionInput(request: InteractionInputRequest): InteractionInputResult {
        val opId = nextOpId()
        val startedAt = System.currentTimeMillis()

        val event = if (config.pendingInteraction != null) {
            config.pendingInteraction
        } else {
            val deferred = inputLock.withLock {
                if (pendingInput != null) {
                    throw SensesError.Rejected("another input request is already pending")
                }
                CompletableDeferred<String>().also { pendingInput = it }
            }
            try {
                val text = withTimeoutOrNull(request.timeoutMillis) { deferred.await() }
                    ?: throw SensesError.Timeout("interaction input timed out")
                InteractionEvent.TextEntry(text = text, timestamp = System.currentTimeMillis())
            } catch (e: Throwable) {
                inputLock.withLock { pendingInput = null }
                throw e
            }
        }

        val completedAt = System.currentTimeMillis()
        val provenance = Provenance(
            operationId = opId,
            endpointId = config.endpointId,
            backendName = config.backendName,
            backendKind = profileValue.backendKind,
            capability = SenseCapability.InteractionInput,
            origin = ResultOrigin.KEYBOARD,
            startedAt = startedAt,
            completedAt = completedAt,
        )
        return InteractionInputResult(event = event, provenance = provenance)
    }

    override suspend fun statusInput(request: StatusInputRequest): StatusInputResult =
        throw SensesError.Unavailable("Chat does not support status input")

    private fun nextOpId(): String = "chat-op-${++operationCounter}"
}
