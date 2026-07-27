package io.github.dornol.mcpwslbridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

class BridgeSettingsTest {
    @Test
    fun `update removes duplicate addresses interfaces and configured distros`() {
        val settings = BridgeSettings()

        settings.update(
            BridgeSettings.State(
                selectedAddresses = mutableListOf("10.0.0.1", "10.0.0.1"),
                selectedInterfaceNames = mutableListOf("vEthernet", "vEthernet"),
                configuredCodexDistros = mutableListOf("Ubuntu", "Ubuntu"),
                configuredClaudeDistros = mutableListOf("Debian", "Debian"),
                configuredCopilotDistros = mutableListOf("Arch", "Arch"),
            ),
        )

        val state = settings.snapshot()
        assertEquals(listOf("10.0.0.1"), state.selectedAddresses)
        assertEquals(listOf("vEthernet"), state.selectedInterfaceNames)
        assertEquals(listOf("Ubuntu"), state.configuredCodexDistros)
        assertEquals(listOf("Debian"), state.configuredClaudeDistros)
        assertEquals(listOf("Arch"), state.configuredCopilotDistros)
    }

    @Test
    fun `snapshot is independent from persisted mutable lists`() {
        val settings = BridgeSettings()
        val snapshot = settings.snapshot()
        snapshot.selectedAddresses.add("192.168.1.10")
        snapshot.configuredCopilotDistros.add("Ubuntu")

        assertEquals(emptyList(), settings.snapshot().selectedAddresses)
        assertEquals(emptyList(), settings.snapshot().configuredCopilotDistros)
        assertNotSame(snapshot.selectedAddresses, settings.snapshot().selectedAddresses)
    }
}
