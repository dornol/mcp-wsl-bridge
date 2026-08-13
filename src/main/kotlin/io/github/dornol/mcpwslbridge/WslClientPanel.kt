package io.github.dornol.mcpwslbridge

import com.intellij.ui.components.JBLabel
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import java.awt.BorderLayout
import java.awt.FlowLayout

/** UI-only WSL client panel. Command execution remains in the configurable/controller. */
class WslClientPanel(
    private val distro: JComboBox<String>,
    private val endpointLabel: JBLabel,
    private val genericConfig: JTextArea,
    private val onRefreshDistros: () -> Unit,
    private val onCopyEndpoint: () -> Unit,
    private val onCopyGeneric: () -> Unit,
    private val onResetHistory: () -> Unit,
    private val onApply: (String, String) -> Unit,
    private val onRemove: (String, String) -> Unit,
    private val onTest: (String, String) -> Unit,
) {
    val applyButtons = mutableMapOf<String, JButton>()
    val statusLabels = mutableMapOf<String, JBLabel>()

    fun create(): JComponent = JPanel(BorderLayout(6, 6)).apply {
        border = BorderFactory.createTitledBorder("WSL Client Configuration")
        add(controls(), BorderLayout.NORTH)
        add(tabs(), BorderLayout.CENTER)
        preferredSize = java.awt.Dimension(900, 190)
    }

    private fun controls(): JPanel = JPanel().apply {
        add(JBLabel("WSL distro:"))
        add(distro)
        add(JButton("Refresh distros").apply { addActionListener { onRefreshDistros() } })
        add(JButton("Copy endpoint").apply { addActionListener { onCopyEndpoint() } })
        add(JButton("Reset imported WSL history").apply { addActionListener { onResetHistory() } })
        add(endpointLabel)
    }

    private fun tabs(): JTabbedPane = JTabbedPane().apply {
        preferredSize = java.awt.Dimension(860, 135)
        addTab("Codex", clientTab("Codex", "codex", "Add or update Codex MCP settings."))
        addTab("Claude Code", clientTab("Claude Code", "claude", "Add or update a user-scoped Claude Code MCP server."))
        addTab("GitHub Copilot CLI", clientTab("GitHub Copilot CLI", "copilot", "Add or update GitHub Copilot CLI MCP settings."))
        addTab("Others", JPanel(BorderLayout(4, 4)).apply {
            add(JBLabel("Generic streamable HTTP MCP JSON for all configured servers:"), BorderLayout.NORTH)
            add(JScrollPane(genericConfig), BorderLayout.CENTER)
            add(JButton("Copy generic JSON").apply { addActionListener { onCopyGeneric() } }, BorderLayout.SOUTH)
        })
    }

    private fun clientTab(name: String, key: String, description: String): JPanel = JPanel(BorderLayout(4, 4)).apply {
        add(JBLabel(description), BorderLayout.NORTH)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            val apply = JButton("Apply to WSL").apply { addActionListener { onApply(name, key) } }
            applyButtons[key] = apply
            add(apply)
            add(JButton("Test connection").apply { addActionListener { onTest(name, key) } })
            add(JButton("Remove from WSL").apply { addActionListener { onRemove(name, key) } })
            val label = JBLabel("Not checked")
            statusLabels[key] = label
            add(label)
        }, BorderLayout.SOUTH)
    }
}
