package com.nyooran.agent.senses.simulator

import com.nyooran.agent.senses.*

/**
 * Runs the shared [SenseEndpointContractTest] suite against the
 * [SimulatedHaloEndpoint] virtual runtime backend.
 */
class SimulatedHaloEndpointContractTest : SenseEndpointContractTest() {

    override val fixture = object : ContractFixture {
        override val name = "SimulatedHaloEndpoint"
        override val supportedCapabilities = setOf(
            SenseCapability.AudioInput,
            SenseCapability.AudioOutput,
            SenseCapability.ImageInput,
            SenseCapability.VisualOutput,
            SenseCapability.InteractionInput,
            SenseCapability.StatusInput,
            SenseCapability.TextOutput,
        )

        override fun createEndpoint() = SimulatedHaloEndpoint(
            endpointId = EndpointId("sim-halo-contract"),
        )
    }
}
