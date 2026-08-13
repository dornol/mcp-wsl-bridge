package io.github.dornol.mcpwslbridge

import kotlin.test.Test
import kotlin.test.assertEquals

class WslClientRouteServiceTest {
    @Test
    fun `remove delegates every route to selected client`() {
        val commands = mutableListOf<List<String>>()
        val previous = WslClientConfigurator.commandRunner
        WslClientConfigurator.commandRunner = { command ->
            commands += command
            WslClientConfigurator.CommandResult(0, "")
        }
        val status = McpBridgeService.Status(
            runningAddresses = listOf("127.0.0.1"), target = null, error = null,
            state = McpBridgeService.State.CONNECTED, listenerPort = 64343,
            lastSuccessfulRefreshTime = null,
            routes = listOf(
                McpBridgeService.RouteStatus("intellij", "IDE", "/stream", McpTarget("127.0.0.1", 1, "test")),
            ),
        )
        try {
            WslClientRouteService({ status }).remove("Ubuntu", "codex")
            assertEquals(true, commands.any { command ->
                command.lastOrNull()?.contains("codex") == true &&
                    command.lastOrNull()?.contains("mcp") == true &&
                    command.lastOrNull()?.contains("remove") == true
            })
        } finally {
            WslClientConfigurator.commandRunner = previous
        }
    }
}
