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

    @Test
    fun `server profiles are copied and legacy settings expose a default profile`() {
        val settings = BridgeSettings().apply {
            update(BridgeSettings.State(targetPort = 29170))
        }
        val profile = settings.serverProfiles().single()
        assertEquals("intellij", profile.id)
        assertEquals("/stream", profile.publicPath)
        assertEquals(29170, profile.targetPort)

        settings.update(
            settings.snapshot().apply {
                servers = mutableListOf(
                    BridgeSettings.ServerProfile(
                        id = "index",
                        displayName = "IDE Index",
                        publicPath = "/mcp/index",
                        targetPort = 29170,
                        targetPath = "/index-mcp/streamable-http",
                    ),
                )
            },
        )
        val snapshot = settings.snapshot()
        snapshot.servers[0].displayName = "changed"
        assertEquals("IDE Index", settings.snapshot().servers[0].displayName)
    }

    @Test
    fun `state can be split into shareable and local parts and restored`() {
        val original = BridgeSettings.State(
            listenerPort = 65000,
            selectedAddresses = mutableListOf("10.0.0.2"),
            wslDistro = "Ubuntu",
            authEnabled = true,
            authToken = "secret",
            configuredClaudeDistros = mutableListOf("Ubuntu"),
        )
        val restored = BridgeSettings.State.from(original.shared(), original.local())
        assertEquals(original.listenerPort, restored.listenerPort)
        assertEquals(original.selectedAddresses, restored.selectedAddresses)
        assertEquals(original.wslDistro, restored.wslDistro)
        assertEquals(original.authToken, restored.authToken)
        assertEquals(original.configuredClaudeDistros, restored.configuredClaudeDistros)
    }
}
