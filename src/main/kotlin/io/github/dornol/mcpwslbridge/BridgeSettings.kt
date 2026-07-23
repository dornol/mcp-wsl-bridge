package io.github.dornol.mcpwslbridge

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(name = "McpWslBridgeSettings", storages = [Storage("mcpWslBridge.xml")])
class BridgeSettings : PersistentStateComponent<BridgeSettings.State> {
    data class State(
        var enabled: Boolean = false,
        var listenerPort: Int = BridgeSettings.DEFAULT_LISTENER_PORT,
        var selectedAddresses: MutableList<String> = mutableListOf(),
        var targetMode: TargetMode = TargetMode.AUTO,
        var targetHost: String = "127.0.0.1",
        var targetPort: Int = BridgeSettings.DEFAULT_MCP_PORT,
    )

    enum class TargetMode { AUTO, MANUAL }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    fun snapshot(): State = state.copy(selectedAddresses = state.selectedAddresses.toMutableList())

    fun update(newState: State) {
        state = newState.copy(selectedAddresses = newState.selectedAddresses.distinct().toMutableList())
    }

    companion object {
        const val DEFAULT_LISTENER_PORT = 64343
        const val DEFAULT_MCP_PORT = 64342

        fun getInstance(): BridgeSettings = ApplicationManager.getApplication().getService(BridgeSettings::class.java)
    }
}
