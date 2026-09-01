package com.nyooran.agent.senses.simulator

import com.nyooran.agent.senses.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatEndpointTest {

    private fun endpoint(config: ChatEndpoint.ChatConfig = ChatEndpoint.ChatConfig()) =
        ChatEndpoint(config)

    // ------------------------------------------------------------------ profile

    @Test
    fun profileAdvertisesOnlyChatCapabilities() {
        val ep = endpoint()
        assertTrue(ep.profile.supports(SenseCapability.TextInput))
        assertTrue(ep.profile.supports(SenseCapability.TextOutput))
        assertTrue(ep.profile.supports(SenseCapability.InteractionInput))
        assertTrue(ep.profile.supports(SenseCapability.ImageInput))  // attachments enabled by default
    }

    @Test
    fun profileDoesNotAdvertiseHaloCapabilities() {
        val ep = endpoint()
        assertFalse(ep.profile.supports(SenseCapability.AudioInput))
        assertFalse(ep.profile.supports(SenseCapability.AudioOutput))
        assertFalse(ep.profile.supports(SenseCapability.VisualOutput))
        assertFalse(ep.profile.supports(SenseCapability.StatusInput))
    }

    @Test
    fun profileBackendKindIsChat() {
        val ep = endpoint()
        assertEquals(BackendKind.CHAT, ep.profile.backendKind)
    }

    @Test
    fun attachmentsDisabledRemovesImageInput() {
        val ep = endpoint(ChatEndpoint.ChatConfig(enableAttachments = false))
        assertFalse(ep.profile.supports(SenseCapability.ImageInput))
    }

    // ------------------------------------------------------------------ connect/disconnect

    @Test
    fun connectTransitionsToReady() = runTest {
        val ep = endpoint()
        ep.connect()
        assertEquals(EndpointState.READY, ep.state.value)
    }

    @Test
    fun disconnectTransitionsToDisconnected() = runTest {
        val ep = endpoint()
        ep.connect()
        ep.disconnect()
        assertEquals(EndpointState.DISCONNECTED, ep.state.value)
    }

    // ------------------------------------------------------------------ text output

    @Test
    fun textOutputRecordsAssistantMessage() = runTest {
        val ep = endpoint()
        ep.connect()
        ep.textOutput(TextOutputRequest(TextContent("Hello from assistant")))
        assertEquals(1, ep.recordedMessages.size)
        assertEquals("assistant", ep.recordedMessages[0].role)
        assertEquals("Hello from assistant", ep.recordedMessages[0].text)
    }

    @Test
    fun textOutputPreservesFormat() = runTest {
        val ep = endpoint()
        ep.connect()
        ep.textOutput(TextOutputRequest(TextContent("**bold**", format = TextFormat.MARKDOWN)))
        assertEquals(TextFormat.MARKDOWN, ep.recordedMessages[0].format)
    }

    @Test
    fun textOutputProvenanceIsChatBackend() = runTest {
        val ep = endpoint()
        ep.connect()
        val result = ep.textOutput(TextOutputRequest(TextContent("hi")))
        assertEquals(BackendKind.CHAT, result.provenance.backendKind)
        assertEquals(SenseCapability.TextOutput, result.provenance.capability)
    }

    // ------------------------------------------------------------------ text input

    @Test
    fun textInputReturnsPendingText() = runTest {
        val ep = endpoint(ChatEndpoint.ChatConfig(pendingTextInput = "user question"))
        ep.connect()
        val result = ep.textInput(TextInputRequest())
        assertEquals("user question", result.text)
    }

    @Test
    fun textInputRecordsUserMessage() = runTest {
        val ep = endpoint(ChatEndpoint.ChatConfig(pendingTextInput = "hello"))
        ep.connect()
        ep.textInput(TextInputRequest())
        assertEquals(1, ep.recordedMessages.size)
        assertEquals("user", ep.recordedMessages[0].role)
        assertEquals("hello", ep.recordedMessages[0].text)
    }

    @Test
    fun textInputProvenanceIsKeyboard() = runTest {
        val ep = endpoint(ChatEndpoint.ChatConfig(pendingTextInput = "hello"))
        ep.connect()
        val result = ep.textInput(TextInputRequest())
        assertEquals(ResultOrigin.KEYBOARD, result.provenance.origin)
    }

    // ------------------------------------------------------------------ interaction

    @Test
    fun interactionInputReturnsPendingEvent() = runTest {
        val ep = endpoint(ChatEndpoint.ChatConfig(
            pendingInteraction = InteractionEvent.Approval(approved = false),
        ))
        ep.connect()
        val result = ep.interactionInput(InteractionInputRequest())
        assertTrue(result.event is InteractionEvent.Approval)
        assertEquals(false, (result.event as InteractionEvent.Approval).approved)
    }

    @Test
    fun interactionInputDefaultsToApproval() = runTest {
        val ep = endpoint(ChatEndpoint.ChatConfig(pendingInteraction = InteractionEvent.Approval(approved = true)))
        ep.connect()
        val result = ep.interactionInput(InteractionInputRequest())
        assertTrue(result.event is InteractionEvent.Approval)
    }

    @Test
    fun interactionSupportsSelection() = runTest {
        val ep = endpoint(ChatEndpoint.ChatConfig(
            pendingInteraction = InteractionEvent.Selection(selectedIndex = 2),
        ))
        ep.connect()
        val result = ep.interactionInput(InteractionInputRequest())
        assertTrue(result.event is InteractionEvent.Selection)
        assertEquals(2, (result.event as InteractionEvent.Selection).selectedIndex)
    }

    // ------------------------------------------------------------------ image input (attachments)

    @Test
    fun imageInputReturnsAttachmentFixture() = runTest {
        val ep = endpoint(ChatEndpoint.ChatConfig(
            imageFixture = byteArrayOf(1, 2, 3, 4),
        ))
        ep.connect()
        val result = ep.imageInput(ImageInputRequest(resolution = 640, maxBytes = 65536))
        assertEquals(4, result.image.size)
        assertEquals(ResultOrigin.ATTACHMENT, result.provenance.origin)
    }

    @Test
    fun imageInputThrowsWhenAttachmentsDisabled() = runTest {
        val ep = endpoint(ChatEndpoint.ChatConfig(enableAttachments = false))
        ep.connect()
        assertFailsWith<SensesError.Unavailable> {
            ep.imageInput(ImageInputRequest(resolution = 640, maxBytes = 65536))
        }
    }

    // ------------------------------------------------------------------ unsupported

    @Test
    fun audioInputThrowsUnavailable() = runTest {
        val ep = endpoint()
        ep.connect()
        assertFailsWith<SensesError.Unavailable> {
            ep.audioInput(AudioInputRequest(maxDurationMillis = 100, maxBytes = 1024))
        }
    }

    @Test
    fun audioOutputThrowsUnavailable() = runTest {
        val ep = endpoint()
        ep.connect()
        assertFailsWith<SensesError.Unavailable> {
            ep.audioOutput(AudioOutputRequest(ByteArray(10), AudioFormat(16000, 16, 1, "wav", "audio/wav")))
        }
    }

    @Test
    fun visualOutputThrowsUnavailable() = runTest {
        val ep = endpoint()
        ep.connect()
        assertFailsWith<SensesError.Unavailable> {
            ep.visualOutput(VisualOutputRequest(VisualContent(VisualContent.VisualKind.TEXT, ByteArray(0))))
        }
    }

    @Test
    fun statusInputThrowsUnavailable() = runTest {
        val ep = endpoint()
        ep.connect()
        assertFailsWith<SensesError.Unavailable> {
            ep.statusInput(StatusInputRequest())
        }
    }

    // ------------------------------------------------------------------ registry integration

    @Test
    fun worksInRegistry() = runTest {
        val registry = SenseEndpointRegistry()
        val ep = endpoint()
        registry.bind(ep)
        val result = registry.resolve(SenseCapability.TextOutput)
        assertTrue(result is ResolveResult.Resolved)
        assertEquals(BackendKind.CHAT, result.endpoint.profile.backendKind)
        registry.unbindAll()
    }

    @Test
    fun chatAndHaloCanCoexistInRegistry() = runTest {
        val registry = SenseEndpointRegistry()
        registry.bind(ChatEndpoint())
        registry.bind(SimulatedHaloEndpoint())
        // TextOutput resolves to chat
        val textResult = registry.resolve(SenseCapability.TextOutput)
        assertTrue(textResult is ResolveResult.Resolved)
        assertEquals(BackendKind.CHAT, textResult.endpoint.profile.backendKind)
        // AudioInput resolves to halo
        val audioResult = registry.resolve(SenseCapability.AudioInput)
        assertTrue(audioResult is ResolveResult.Resolved)
        assertEquals(BackendKind.SIMULATOR, audioResult.endpoint.profile.backendKind)
        registry.unbindAll()
    }
}
