package io.github.dornol.mcpwslbridge

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JScrollPane

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
        panel.add(JButton("Refresh interfaces").apply { addActionListener { populateInterfaces(selectedAddresses()) } }, c)
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
            targetHost.text != state.targetHost || targetPort.text.toIntOrNull() != state.targetPort
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

    private fun updateStatus() {
        val current = McpBridgeService.getInstance().status()
        status.text = when {
            current.error != null -> "Status: ${current.error}"
            current.runningAddresses.isNotEmpty() -> "Status: listening on ${current.runningAddresses.joinToString()} → ${current.target?.host}:${current.target?.port}"
            else -> "Status: stopped"
        }
    }
}

