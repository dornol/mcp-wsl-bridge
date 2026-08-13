package io.github.dornol.mcpwslbridge

/** Applies or removes every active MCP route for one WSL CLI. */
class WslClientRouteService(
    private val statusProvider: () -> McpBridgeService.Status = { McpBridgeService.getInstance().status() },
) {
    fun configure(
        distro: String,
        client: String,
        onRoute: (String) -> Unit = {},
    ): WslClientConfigurator.CommandResult {
        val current = statusProvider()
        val address = current.runningAddresses.firstOrNull()
            ?: return WslClientConfigurator.CommandResult(1, current.error ?: "Bridge is not listening.")
        val base = "http://$address:${current.listenerPort}"
        for (route in current.routes.filter { it.target != null }) {
            val serverName = WslClientConfigurator.serverNameForRoute(route.id)
            onRoute(serverName)
            val endpoint = "$base${route.publicPath}"
            val result = when (client) {
                "codex" -> WslClientConfigurator.configureCodex(distro, endpoint, serverName)
                "claude" -> WslClientConfigurator.configureClaudeCode(distro, endpoint, serverName)
                else -> WslClientConfigurator.configureCopilotCli(distro, endpoint, serverName)
            }
            if (!result.succeeded) return result
        }
        return WslClientConfigurator.CommandResult(0, "Configured ${current.routes.size} MCP routes.")
    }

    fun remove(distro: String, client: String): WslClientConfigurator.CommandResult {
        val routeNames = statusProvider().routes.map { WslClientConfigurator.serverNameForRoute(it.id) }
        var firstFailure: WslClientConfigurator.CommandResult? = null
        routeNames.forEach { serverName ->
            val result = when (client) {
                "codex" -> WslClientConfigurator.removeCodex(distro, serverName)
                "claude" -> WslClientConfigurator.removeClaudeCode(distro, serverName)
                else -> WslClientConfigurator.removeCopilotCli(distro, serverName)
            }
            if (!result.succeeded && firstFailure == null) firstFailure = result
        }
        return firstFailure ?: WslClientConfigurator.CommandResult(0, "Removed ${routeNames.size} MCP routes.")
    }
}
