package io.github.dornol.mcpwslbridge

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(name = "McpWslBridgeSettings", storages = [Storage("mcpWslBridge.xml")])
class BridgeSettings : PersistentStateComponent<BridgeSettings.State> {
    enum class ServerType { INTELLIJ_BUILT_IN, HTTP }

    data class ServerProfile(
        var id: String = "",
        var displayName: String = "",
        var enabled: Boolean = true,
        var publicPath: String = "",
        var targetHost: String = "127.0.0.1",
        var targetPort: Int = 0,
        var targetPath: String = "",
        var targetMode: TargetMode = TargetMode.MANUAL,
        var serverType: ServerType = ServerType.HTTP,
    )

    data class State(
        var enabled: Boolean = false,
        var listenerPort: Int = BridgeSettings.DEFAULT_LISTENER_PORT,
        var selectedAddresses: MutableList<String> = mutableListOf(),
        var selectedInterfaceNames: MutableList<String> = mutableListOf(),
        var targetMode: TargetMode = TargetMode.AUTO,
        var targetHost: String = "127.0.0.1",
        var targetPort: Int = BridgeSettings.DEFAULT_MCP_PORT,
        var wslDistro: String = "",
        var codexConfigured: Boolean = false,
        var claudeConfigured: Boolean = false,
        var configuredCodexDistros: MutableList<String> = mutableListOf(),
        var configuredClaudeDistros: MutableList<String> = mutableListOf(),
        var configuredCopilotDistros: MutableList<String> = mutableListOf(),
        var servers: MutableList<ServerProfile> = mutableListOf(),
    )

    enum class TargetMode { AUTO, MANUAL }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    fun snapshot(): State = state.copy(
        selectedAddresses = state.selectedAddresses.toMutableList(),
        selectedInterfaceNames = state.selectedInterfaceNames.toMutableList(),
        configuredCodexDistros = state.configuredCodexDistros.toMutableList(),
        configuredClaudeDistros = state.configuredClaudeDistros.toMutableList(),
        configuredCopilotDistros = state.configuredCopilotDistros.toMutableList(),
        servers = state.servers.map { it.copy() }.toMutableList(),
    )

    fun update(newState: State) {
        state = newState.copy(
            selectedAddresses = newState.selectedAddresses.distinct().toMutableList(),
            selectedInterfaceNames = newState.selectedInterfaceNames.distinct().toMutableList(),
            configuredCodexDistros = newState.configuredCodexDistros.distinct().toMutableList(),
            configuredClaudeDistros = newState.configuredClaudeDistros.distinct().toMutableList(),
            configuredCopilotDistros = newState.configuredCopilotDistros.distinct().toMutableList(),
            servers = newState.servers.map { it.copy() }.toMutableList(),
        )
    }

    fun serverProfiles(): List<ServerProfile> {
        val current = snapshot()
        if (current.servers.isNotEmpty()) return current.servers
        return listOf(
            ServerProfile(
                id = "intellij",
                displayName = "IntelliJ MCP",
                publicPath = "/stream",
                targetHost = current.targetHost,
                targetPort = current.targetPort,
                targetPath = "/stream",
                targetMode = current.targetMode,
                serverType = ServerType.INTELLIJ_BUILT_IN,
            ),
        )
    }

    companion object {
        const val DEFAULT_LISTENER_PORT = 64343
        const val DEFAULT_MCP_PORT = 64342

        fun getInstance(): BridgeSettings = ApplicationManager.getApplication().getService(BridgeSettings::class.java)
    }
}
