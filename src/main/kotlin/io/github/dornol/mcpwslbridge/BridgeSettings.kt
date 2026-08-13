package io.github.dornol.mcpwslbridge

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.util.UUID

private fun applicationSharedSettings(): BridgeSharedSettings =
    ApplicationManager.getApplication().getService(BridgeSharedSettings::class.java)

private fun applicationLocalSettings(): BridgeLocalSettings =
    ApplicationManager.getApplication().getService(BridgeLocalSettings::class.java)

@State(
    name = "McpWslBridgeSettings",
    storages = [Storage(value = "mcpWslBridge.xml", roamingType = RoamingType.DISABLED)],
)
class BridgeSettings(
    private val sharedProvider: () -> BridgeSharedSettings = { applicationSharedSettings() },
    private val localProvider: () -> BridgeLocalSettings = { applicationLocalSettings() },
) : PersistentStateComponent<BridgeSettings.State> {
    private val sharedSettings = runCatching { sharedProvider() }.getOrElse { BridgeSharedSettings() }
    private val localSettings = runCatching { localProvider() }.getOrElse { BridgeLocalSettings() }
    private var legacyState: State? = null
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
        var endpointAddress: String = "",
        var authEnabled: Boolean = false,
        var authToken: String = "",
        var autoRefreshClients: Boolean = true,
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
    ) {
        fun shared(): SharedState = SharedState(
            enabled, listenerPort, selectedAddresses.toMutableList(), selectedInterfaceNames.toMutableList(),
            endpointAddress, targetMode, targetHost, targetPort, servers.map { it.copy() }.toMutableList(),
        )

        fun local(): LocalState = LocalState(
            wslDistro, codexConfigured, claudeConfigured,
            configuredCodexDistros.toMutableList(), configuredClaudeDistros.toMutableList(),
            configuredCopilotDistros.toMutableList(), authEnabled, authToken, autoRefreshClients,
        )

        companion object {
            fun from(shared: SharedState, local: LocalState): State = State(
                enabled = shared.enabled,
                listenerPort = shared.listenerPort,
                selectedAddresses = shared.selectedAddresses.toMutableList(),
                selectedInterfaceNames = shared.selectedInterfaceNames.toMutableList(),
                endpointAddress = shared.endpointAddress,
                authEnabled = local.authEnabled,
                authToken = local.authToken,
                autoRefreshClients = local.autoRefreshClients,
                targetMode = shared.targetMode,
                targetHost = shared.targetHost,
                targetPort = shared.targetPort,
                wslDistro = local.wslDistro,
                codexConfigured = local.codexConfigured,
                claudeConfigured = local.claudeConfigured,
                configuredCodexDistros = local.configuredCodexDistros.toMutableList(),
                configuredClaudeDistros = local.configuredClaudeDistros.toMutableList(),
                configuredCopilotDistros = local.configuredCopilotDistros.toMutableList(),
                servers = shared.servers.map { it.copy() }.toMutableList(),
            )
        }
    }

    /** Settings that are safe to share between IDE installations. */
    data class SharedState(
        var enabled: Boolean = false,
        var listenerPort: Int = BridgeSettings.DEFAULT_LISTENER_PORT,
        var selectedAddresses: MutableList<String> = mutableListOf(),
        var selectedInterfaceNames: MutableList<String> = mutableListOf(),
        var endpointAddress: String = "",
        var targetMode: TargetMode = TargetMode.AUTO,
        var targetHost: String = "127.0.0.1",
        var targetPort: Int = BridgeSettings.DEFAULT_MCP_PORT,
        var servers: MutableList<ServerProfile> = mutableListOf(),
    )

    /** Settings tied to the local Windows/WSL installation. */
    data class LocalState(
        var wslDistro: String = "",
        var codexConfigured: Boolean = false,
        var claudeConfigured: Boolean = false,
        var configuredCodexDistros: MutableList<String> = mutableListOf(),
        var configuredClaudeDistros: MutableList<String> = mutableListOf(),
        var configuredCopilotDistros: MutableList<String> = mutableListOf(),
        var authEnabled: Boolean = false,
        var authToken: String = "",
        var autoRefreshClients: Boolean = true,
    )

    enum class TargetMode { AUTO, MANUAL }

    private fun combined(): State {
        val legacy = legacyState
        return if (legacy != null) legacy else State.from(sharedSettings.snapshot(), localSettings.snapshot())
    }

    // The old combined file is retained only as a one-time migration reader.
    // New data is persisted by BridgeSharedSettings and BridgeLocalSettings.
    override fun getState(): State = State()

    override fun loadState(state: State) {
        // Compatibility path for the old combined mcpWslBridge.xml file.
        if (state != State()) {
            sharedSettings.update(state.shared())
            localSettings.update(state.local())
        }
        legacyState = null
    }

    fun snapshot(): State = combined().let { value ->
        value.copy(
            selectedAddresses = value.selectedAddresses.toMutableList(),
            selectedInterfaceNames = value.selectedInterfaceNames.toMutableList(),
            configuredCodexDistros = value.configuredCodexDistros.toMutableList(),
            configuredClaudeDistros = value.configuredClaudeDistros.toMutableList(),
            configuredCopilotDistros = value.configuredCopilotDistros.toMutableList(),
            servers = value.servers.map { it.copy() }.toMutableList(),
        )
    }

    fun update(newState: State) {
        sharedSettings.update(newState.shared())
        localSettings.update(newState.local())
        legacyState = null
    }

    fun serverProfiles(): List<ServerProfile> {
        val current = snapshot()
        if (current.servers.isNotEmpty()) return current.servers
        return listOf(
            ServerProfile(
                id = "intellij",
                displayName = "Built-in IDE MCP",
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

        fun newAuthToken(): String = UUID.randomUUID().toString().replace("-", "")

        fun getInstance(): BridgeSettings = ApplicationManager.getApplication().getService(BridgeSettings::class.java)
    }
}
