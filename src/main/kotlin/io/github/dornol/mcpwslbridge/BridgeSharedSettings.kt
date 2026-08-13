package io.github.dornol.mcpwslbridge

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(
    name = "McpWslBridgeSharedSettings",
    storages = [Storage(value = "mcpWslBridgeShared.xml", roamingType = RoamingType.DEFAULT)],
)
class BridgeSharedSettings : PersistentStateComponent<BridgeSettings.SharedState> {
    private var state = BridgeSettings.SharedState()
    override fun getState(): BridgeSettings.SharedState = state
    override fun loadState(state: BridgeSettings.SharedState) { this.state = state }
    fun snapshot(): BridgeSettings.SharedState = state.copy(
        selectedAddresses = state.selectedAddresses.toMutableList(),
        selectedInterfaceNames = state.selectedInterfaceNames.toMutableList(),
        servers = state.servers.map { it.copy() }.toMutableList(),
    )
    fun update(value: BridgeSettings.SharedState) {
        state = value.copy(
            selectedAddresses = value.selectedAddresses.distinct().toMutableList(),
            selectedInterfaceNames = value.selectedInterfaceNames.distinct().toMutableList(),
            servers = value.servers.map { it.copy() }.toMutableList(),
        )
    }
}
