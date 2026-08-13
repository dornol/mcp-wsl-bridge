package io.github.dornol.mcpwslbridge

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(
    name = "McpWslBridgeLocalSettings",
    storages = [Storage(value = "mcpWslBridgeLocal.xml", roamingType = RoamingType.DISABLED)],
)
class BridgeLocalSettings : PersistentStateComponent<BridgeSettings.LocalState> {
    private var state = BridgeSettings.LocalState()
    private val tokenStore = BridgeAuthTokenStore()
    override fun getState(): BridgeSettings.LocalState = state.copy(authToken = "")
    override fun loadState(state: BridgeSettings.LocalState) {
        if (state.authToken.isNotBlank()) tokenStore.set(state.authToken)
        this.state = state.copy(authToken = "")
    }
    fun snapshot(): BridgeSettings.LocalState = state.copy(
        authToken = tokenStore.get(),
        configuredCodexDistros = state.configuredCodexDistros.toMutableList(),
        configuredClaudeDistros = state.configuredClaudeDistros.toMutableList(),
        configuredCopilotDistros = state.configuredCopilotDistros.toMutableList(),
    )
    fun update(value: BridgeSettings.LocalState) {
        tokenStore.set(value.authToken)
        state = value.copy(
            authToken = "",
            configuredCodexDistros = value.configuredCodexDistros.distinct().toMutableList(),
            configuredClaudeDistros = value.configuredClaudeDistros.distinct().toMutableList(),
            configuredCopilotDistros = value.configuredCopilotDistros.distinct().toMutableList(),
        )
    }
}
