package com.nyooran.agent.senses.halo

import com.nyooran.agent.senses.*
import com.nyooran.agent.senses.simulator.Scenario
import com.nyooran.agent.senses.simulator.SimulatedHaloBleTransport

/**
 * Runs the shared [SenseEndpointContractTest] suite against a
 * [PhysicalHaloEndpoint] backed by a fake [SimulatedHaloBleTransport].
 */
class PhysicalHaloEndpointContractTest : SenseEndpointContractTest() {

    override val fixture = object : ContractFixture {
        override val name = "PhysicalHaloEndpoint"
        override val supportedCapabilities = setOf(
            SenseCapability.AudioInput,
            SenseCapability.AudioOutput,
            SenseCapability.ImageInput,
            SenseCapability.VisualOutput,
            SenseCapability.InteractionInput,
            SenseCapability.StatusInput,
        )

        override fun createEndpoint() = PhysicalHaloEndpoint(
            transport = SimulatedHaloBleTransport(
                scenario = Scenario(audioFixture = ByteArray(512)),
            ),
            config = PhysicalHaloEndpoint.HaloEndpointConfig(
                endpointId = EndpointId("halo-contract"),
                displayName = "Physical Halo (Contract)",
                runtimeInstaller = { },
            ),
        )
    }
}
