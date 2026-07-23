package io.github.dornol.mcpwslbridge

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.datatransfer.StringSelection
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTextArea

class BridgeConfigurable : Configurable {
    private var root: JPanel? = null
    private val enabled = JBCheckBox("Enable MCP WSL Bridge")
    private val listenerPort = JBTextField()
    private val autoTarget = JRadioButton("Automatically detect IntelliJ MCP port", true)
    private val manualTarget = JRadioButton("Use manual target")
    private val targetHost = JBTextField()
    private val targetPort = JBTextField()
    private val interfacePanel = JPanel().apply { layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS) }
    private val interfaceChecks = linkedMapOf<String, JBCheckBox>()
    private val distro = JComboBox<String>()
    private val clientEndpoint = JBLabel()
    private val genericConfig = JTextArea(8, 52).apply { isEditable = false; lineWrap = false }
    private val status = JBLabel()

    override fun getDisplayName() = "MCP WSL Bridge"

    override fun createComponent(): JComponent {
        if (root != null) return root!!
        val panel = JPanel(GridBagLayout())
        val c = GridBagConstraints().apply {
            gridx = 0; gridy = 0; anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL; weightx = 1.0; insets = Insets(4, 4, 4, 4)
        }
        panel.add(enabled, c)
        c.gridy++
        panel.add(JBLabel("Listener port (one listener is opened on each selected address):"), c)
        c.gridy++
        panel.add(listenerPort, c)
        c.gridy++
        panel.add(JBLabel("Network interfaces — only checked IPv4 addresses accept connections:"), c)
        c.gridy++
        panel.add(JScrollPane(interfacePanel).apply { preferredSize = java.awt.Dimension(560, 140) }, c)
        c.gridy++
        panel.add(JButton("Refresh interfaces").apply { addActionListener { populateInterfaces(selectedAddresses().toSet()) } }, c)
        c.gridy++
        panel.add(autoTarget, c)
        c.gridy++
        panel.add(manualTarget, c)
        ButtonGroup().apply { add(autoTarget); add(manualTarget) }
        c.gridy++
        panel.add(JBLabel("Manual MCP target host:"), c)
        c.gridy++
        panel.add(targetHost, c)
        c.gridy++
        panel.add(JBLabel("Manual MCP target port:"), c)
        c.gridy++
        panel.add(targetPort, c)
        c.gridy++
        panel.add(clientConfigurationPanel(), c)
        c.gridy++
        panel.add(status, c)
        root = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
            add(panel, BorderLayout.NORTH)
        }
        reset()
        return root!!
    }

    override fun isModified(): Boolean {
        val state = BridgeSettings.getInstance().snapshot()
        return enabled.isSelected != state.enabled ||
            listenerPort.text.toIntOrNull() != state.listenerPort ||
            selectedAddresses().toSet() != state.selectedAddresses.toSet() ||
            (if (autoTarget.isSelected) BridgeSettings.TargetMode.AUTO else BridgeSettings.TargetMode.MANUAL) != state.targetMode ||
            targetHost.text != state.targetHost || targetPort.text.toIntOrNull() != state.targetPort ||
            (distro.selectedItem as? String ?: "") != state.wslDistro
    }

    override fun apply() {
        val port = listenerPort.text.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: throw IllegalArgumentException("Listener port must be between 1 and 65535.")
        val manualPort = targetPort.text.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: throw IllegalArgumentException("Target port must be between 1 and 65535.")
        BridgeSettings.getInstance().update(
            BridgeSettings.State(
                enabled = enabled.isSelected,
                listenerPort = port,
                selectedAddresses = selectedAddresses().toMutableList(),
                targetMode = if (autoTarget.isSelected) BridgeSettings.TargetMode.AUTO else BridgeSettings.TargetMode.MANUAL,
                targetHost = targetHost.text.trim(),
                targetPort = manualPort,
                wslDistro = distro.selectedItem as? String ?: "",
            ),
        )
        McpBridgeService.getInstance().restart()
        updateStatus()
    }

    override fun reset() {
        val state = BridgeSettings.getInstance().snapshot()
        enabled.isSelected = state.enabled
        listenerPort.text = state.listenerPort.toString()
        autoTarget.isSelected = state.targetMode == BridgeSettings.TargetMode.AUTO
        manualTarget.isSelected = !autoTarget.isSelected
        targetHost.text = state.targetHost
        targetPort.text = state.targetPort.toString()
        populateInterfaces(state.selectedAddresses.toSet())
        populateDistributions(state.wslDistro)
        updateStatus()
    }

    override fun disposeUIResources() {
        root = null
        interfaceChecks.clear()
    }

    private fun populateInterfaces(selected: Set<String>) {
        interfacePanel.removeAll()
        interfaceChecks.clear()
        NetworkInterfaces.availableIpv4Addresses().forEach { item ->
            JBCheckBox(item.label, item.address in selected || (selected.isEmpty() && item.suggestedForWsl)).also { check ->
                interfaceChecks[item.address] = check
                interfacePanel.add(check)
            }
        }
        interfacePanel.revalidate()
        interfacePanel.repaint()
    }

    private fun selectedAddresses(): List<String> = interfaceChecks.filterValues { it.isSelected }.keys.toList()

    private fun clientConfigurationPanel(): JComponent {
        val panel = JPanel(BorderLayout(6, 6)).apply {
            border = BorderFactory.createTitledBorder("WSL Client Configuration")
        }
        val controls = JPanel().apply {
            add(JBLabel("WSL distro:"))
            add(distro)
            add(JButton("Refresh distros").apply { addActionListener { populateDistributions(distro.selectedItem as? String ?: "") } })
            add(clientEndpoint)
        }
        val tabs = JTabbedPane()
        tabs.addTab("Codex", clientActionPanel("Add or update '${WslClientConfigurator.SERVER_NAME}' in ~/.codex/config.toml.") {
            applyWslConfiguration("Codex") { selectedDistro, endpoint -> WslClientConfigurator.configureCodex(selectedDistro, endpoint) }
        })
        tabs.addTab("Claude Code", clientActionPanel("Add or update a user-scoped '${WslClientConfigurator.SERVER_NAME}' MCP server.") {
            applyWslConfiguration("Claude Code") { selectedDistro, endpoint -> WslClientConfigurator.configureClaudeCode(selectedDistro, endpoint) }
        })
        tabs.addTab("Others", JPanel(BorderLayout(4, 4)).apply {
            add(JBLabel("Generic streamable HTTP MCP JSON:"), BorderLayout.NORTH)
            add(JScrollPane(genericConfig), BorderLayout.CENTER)
            add(JButton("Copy generic JSON").apply {
                addActionListener {
                    runCatching { endpoint() }
                        .onSuccess { CopyPasteManager.getInstance().setContents(StringSelection(WslClientConfigurator.genericJson(it))) }
                        .onFailure { Messages.showErrorDialog(it.message ?: "Bridge is not listening.", "MCP WSL Bridge") }
                }
            }, BorderLayout.SOUTH)
        })
        panel.add(controls, BorderLayout.NORTH)
        panel.add(tabs, BorderLayout.CENTER)
        return panel
    }

    private fun clientActionPanel(description: String, action: () -> Unit): JComponent = JPanel(BorderLayout(4, 4)).apply {
        add(JBLabel(description), BorderLayout.NORTH)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(JButton("Apply to WSL").apply { addActionListener { action() } })
        }, BorderLayout.SOUTH)
    }

    private fun populateDistributions(selected: String) {
        distro.removeAllItems()
        WslClientConfigurator.distributions().forEach(distro::addItem)
        if (selected.isNotBlank()) distro.selectedItem = selected
        if (distro.selectedIndex < 0 && distro.itemCount > 0) distro.selectedIndex = 0
    }

    private fun applyWslConfiguration(clientName: String, action: (String, String) -> WslClientConfigurator.CommandResult) {
        val selectedDistro = distro.selectedItem as? String
        val bridgeEndpoint = runCatching { endpoint() }.getOrElse { error ->
            Messages.showErrorDialog(error.message ?: "Bridge is not listening.", "MCP WSL Bridge")
            return
        }
        if (selectedDistro.isNullOrBlank()) {
            Messages.showErrorDialog("Choose a WSL distribution first.", "MCP WSL Bridge")
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = action(selectedDistro, bridgeEndpoint)
            ApplicationManager.getApplication().invokeLater {
                if (result.succeeded) {
                    status.text = "$clientName configured in WSL '$selectedDistro': $bridgeEndpoint"
                } else {
                    Messages.showErrorDialog(result.output.ifBlank { "Configuration command failed." }, "MCP WSL Bridge")
                }
            }
        }
    }

    private fun endpoint(): String {
        val bridge = McpBridgeService.getInstance().status()
        val address = bridge.runningAddresses.firstOrNull()
            ?: throw IllegalStateException(bridge.error ?: "Enable the bridge and select a network interface first.")
        return "http://$address:${BridgeSettings.getInstance().snapshot().listenerPort}/stream"
    }

    private fun updateStatus() {
        val current = McpBridgeService.getInstance().status()
        clientEndpoint.text = runCatching { "WSL endpoint: ${endpoint()}" }.getOrElse { "WSL endpoint: start the bridge first" }
        genericConfig.text = runCatching { WslClientConfigurator.genericJson(endpoint()) }.getOrDefault("Start the bridge to generate a configuration.")
        status.text = when {
            current.error != null -> "Status: ${current.error}"
            current.runningAddresses.isNotEmpty() -> "Status: listening on ${current.runningAddresses.joinToString()} → ${current.target?.host}:${current.target?.port}"
            else -> "Status: stopped"
        }
    }
}
