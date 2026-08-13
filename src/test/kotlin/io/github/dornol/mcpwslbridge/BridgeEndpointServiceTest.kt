package io.github.dornol.mcpwslbridge

import kotlin.test.Test
import kotlin.test.assertEquals

class BridgeEndpointServiceTest {
    @Test
    fun `selected endpoint address is preferred`() {
        val state = BridgeSettings.State(
            listenerPort = 64343,
            endpointAddress = "10.0.0.2",
        )
        val status = McpBridgeService.Status(
            runningAddresses = listOf("10.0.0.1", "10.0.0.2"),
            target = null,
            error = null,
            state = McpBridgeService.State.CONNECTED,
            listenerPort = 64343,
            lastSuccessfulRefreshTime = null,
        )
        val service = BridgeEndpointService({ status }, { state })
        assertEquals("http://10.0.0.2:64343/stream", service.endpoint())
    }
}
