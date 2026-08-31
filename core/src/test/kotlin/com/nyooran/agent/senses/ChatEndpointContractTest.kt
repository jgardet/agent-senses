package com.nyooran.agent.senses

/**
 * Runs the shared endpoint contract suite against a chat-like endpoint
 * with reduced capabilities (text only, no audio/camera/battery).
 * This verifies the contract suite correctly handles partial endpoints.
 */
class ChatEndpointContractTest : SenseEndpointContractTest() {

    override val fixture = object : ContractFixture {
        override val name = "ChatEndpoint"
        override val supportedCapabilities = setOf(
            SenseCapability.TextInput,
            SenseCapability.TextOutput,
            SenseCapability.InteractionInput,
        )

        override fun createEndpoint() = FakeSenseEndpoint(
            endpointId = EndpointId("chat-1"),
            capabilities = supportedCapabilities,
            backendKind = BackendKind.CHAT,
        )
    }
}
