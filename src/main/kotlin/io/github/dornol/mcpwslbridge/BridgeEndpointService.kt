package io.github.dornol.mcpwslbridge

class BridgeEndpointService(
    private val statusProvider: () -> McpBridgeService.Status = { McpBridgeService.getInstance().status() },
    private val settingsProvider: () -> BridgeSettings.State = { BridgeSettings.getInstance().snapshot() },
) {
    fun endpoint(path: String = "/stream"): String = endpoint(path, statusProvider())

    private fun endpoint(path: String, status: McpBridgeService.Status): String {
        val state = settingsProvider()
        val address = state.endpointAddress.takeIf { it in status.runningAddresses }
            ?: status.runningAddresses.firstOrNull()
            ?: throw IllegalStateException(status.error ?: "Enable the bridge and select a network interface first.")
        return McpEndpoint.url(address, state.listenerPort, path, state)
    }

    fun endpoints(status: McpBridgeService.Status = statusProvider()): Map<String, String> {
        val state = settingsProvider()
        return status.routes.associate { route ->
            WslClientConfigurator.serverNameForRoute(route.id) to endpoint(route.publicPath, status)
        }
    }
}
