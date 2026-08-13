package io.github.dornol.mcpwslbridge

import kotlin.test.Test
import kotlin.test.assertEquals

class McpEndpointTest {
    @Test
    fun `authenticated endpoint includes token`() {
        val state = BridgeSettings.State(authEnabled = true, authToken = "secret")
        assertEquals(
            "http://172.20.1.1:64343/stream?token=secret",
            McpEndpoint.url("172.20.1.1", 64343, "/stream", state),
        )
    }

    @Test
    fun `endpoint omits token when authentication is disabled`() {
        val state = BridgeSettings.State(authEnabled = false, authToken = "secret")
        assertEquals(
            "http://127.0.0.1:64343/stream",
            McpEndpoint.url("127.0.0.1", 64343, "/stream", state),
        )
    }

    @Test
    fun `endpoint encodes manually entered token`() {
        val state = BridgeSettings.State(authEnabled = true, authToken = "a+b c")
        assertEquals(
            "http://127.0.0.1:64343/stream?token=a%2Bb+c",
            McpEndpoint.url("127.0.0.1", 64343, "/stream", state),
        )
    }
}
