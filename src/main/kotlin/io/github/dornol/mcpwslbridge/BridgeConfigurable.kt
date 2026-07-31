package io.github.dornol.mcpwslbridge

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.datatransfer.StringSelection
import java.awt.Toolkit
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.JTextArea
import javax.swing.table.DefaultTableModel

class BridgeConfigurable : Configurable {
    private var root: JPanel? = null
    private val enabled = JBCheckBox("Enable MCP WSL Bridge and start it automatically with IntelliJ")
    private val listenerPort = JBTextField()
    private val serverTableModel = object : DefaultTableModel(SERVER_COLUMNS, 0) {
        override fun getColumnClass(column: Int): Class<*> = if (column == 0) Boolean::class.java else String::class.java

        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
    private val serverTable = JTable(serverTableModel).apply {
        autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
        setShowGrid(true)
        columnModel.getColumn(0).preferredWidth = 55
        columnModel.getColumn(1).preferredWidth = 105
        columnModel.getColumn(2).preferredWidth = 150
        columnModel.getColumn(3).preferredWidth = 105
        columnModel.getColumn(4).preferredWidth = 190
        columnModel.getColumn(5).preferredWidth = 135
        columnModel.getColumn(6).preferredWidth = 80
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2 && event.button == MouseEvent.BUTTON1) editSelectedServer()
            }
        })
    }
    private val interfacePanel = JPanel().apply { layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS) }
    private val interfaceChecks = linkedMapOf<String, JBCheckBox>()
    private val interfaceNamesByAddress = linkedMapOf<String, String>()
    private val distro = JComboBox<String>()
    private val clientEndpoint = JBLabel()
    private val genericConfig = JTextArea(4, 52).apply { isEditable = false; lineWrap = false }
    private val status = JBLabel()
    private var distributionsLoading = false
    private var distributionLoadGeneration = 0

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
        panel.add(serverConfigurationPanel(), c)
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

    private fun serverConfigurationPanel(): JComponent = JPanel(BorderLayout(0, 4)).apply {
        add(JBLabel("MCP servers exposed through the WSL bridge:"), BorderLayout.NORTH)
        add(JScrollPane(serverTable).apply { preferredSize = java.awt.Dimension(900, 150) }, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                add(JButton("+").apply {
                    toolTipText = "Add MCP server"
                    addActionListener { openServerDialog() }
                })
                add(JButton("✎").apply {
                    toolTipText = "Edit selected MCP server"
                    addActionListener { editSelectedServer() }
                })
                add(JButton("−").apply {
                    toolTipText = "Remove selected MCP server"
                    addActionListener { removeSelectedServers() }
                })
                add(JButton("Add IDE Index MCP").apply {
                    toolTipText = "Add the IDE Index MCP server preset"
                    addActionListener { addPreset(indexProfile()) }
                })
            }, BorderLayout.WEST)
            add(JBLabel("  IntelliJ MCP is always enabled and detects its port automatically."), BorderLayout.CENTER)
        }, BorderLayout.SOUTH)
    }

    private fun addCustomServer() {
        openServerDialog()
    }

    private fun editSelectedServer() {
        val row = serverTable.selectedRow
        if (row < 0) return
        if (serverTableModel.getValueAt(row, ID_COLUMN)?.toString() == "intellij") {
            Messages.showInfoMessage(
                "The built-in IntelliJ MCP server is always enabled and cannot be changed or removed.",
                "MCP WSL Bridge",
            )
            return
        }
        openServerDialog(row)
    }

    private fun openServerDialog(row: Int? = null) {
        val existing = row?.let { serverProfileAt(it) }
        val dialog = McpServerDialog(existing)
        if (!dialog.showAndGet()) return
        val profile = dialog.profile()
        val duplicate = (0 until serverTableModel.rowCount).any { index ->
            index != row && serverTableModel.getValueAt(index, ID_COLUMN)?.toString() == profile.id
        }
        if (duplicate) {
            Messages.showErrorDialog("MCP server IDs must be unique.", "MCP WSL Bridge")
            return
        }
        val values = arrayOf<Any?>(
            profile.enabled,
            profile.id,
            profile.displayName,
            "http",
            profile.publicPath,
            profile.targetHost,
            profile.targetPort.toString(),
        )
        if (row == null) {
            serverTableModel.addRow(values)
            serverTable.setRowSelectionInterval(serverTableModel.rowCount - 1, serverTableModel.rowCount - 1)
        } else {
            values.forEachIndexed { column, value -> serverTableModel.setValueAt(value, row, column) }
            serverTable.setRowSelectionInterval(row, row)
        }
    }

    private fun serverProfileAt(row: Int): BridgeSettings.ServerProfile = BridgeSettings.ServerProfile(
        id = serverTableModel.getValueAt(row, 1).toString(),
        displayName = serverTableModel.getValueAt(row, 2).toString(),
        enabled = serverTableModel.getValueAt(row, 0) == true,
        publicPath = serverTableModel.getValueAt(row, 4).toString(),
        targetHost = serverTableModel.getValueAt(row, 5).toString(),
        targetPort = serverTableModel.getValueAt(row, 6).toString().toIntOrNull() ?: 0,
        targetPath = serverTableModel.getValueAt(row, 4).toString(),
        targetMode = BridgeSettings.TargetMode.MANUAL,
        serverType = BridgeSettings.ServerType.HTTP,
    )

    private fun removeSelectedServers() {
        val protectedRows = serverTable.selectedRows.filter {
            serverTableModel.getValueAt(it, ID_COLUMN)?.toString() == "intellij"
        }
        if (protectedRows.isNotEmpty()) {
            Messages.showInfoMessage(
                "The built-in IntelliJ MCP server is always enabled and cannot be changed or removed.",
                "MCP WSL Bridge",
            )
        }
        serverTable.selectedRows
            .filterNot { it in protectedRows }
            .sortedDescending()
            .forEach(serverTableModel::removeRow)
    }

    override fun isModified(): Boolean {
        val state = BridgeSettings.getInstance().snapshot()
        return enabled.isSelected != state.enabled ||
            listenerPort.text.toIntOrNull() != state.listenerPort ||
            selectedInterfaceNames().toSet() != storedInterfaceNames(state) ||
            tableProfiles() != uiProfiles(state) ||
            (!distributionsLoading && (distro.selectedItem as? String ?: "") != state.wslDistro)
    }

    override fun apply() {
        val port = listenerPort.text.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: throw IllegalArgumentException("Listener port must be between 1 and 65535.")
        val profiles = tableProfiles()
        val currentState = BridgeSettings.getInstance().snapshot()
        BridgeSettings.getInstance().update(
            currentState.copy(
                enabled = enabled.isSelected,
                listenerPort = port,
                selectedAddresses = selectedAddresses().toMutableList(),
                selectedInterfaceNames = selectedInterfaceNames().toMutableList(),
                servers = profiles.toMutableList(),
                wslDistro = if (distributionsLoading) currentState.wslDistro else distro.selectedItem as? String ?: "",
            ),
        )
        status.text = "Status: applying bridge settings..."
        McpBridgeService.getInstance().restart {
            ApplicationManager.getApplication().invokeLater {
                if (root != null) updateStatus()
            }
        }
    }

    override fun reset() {
        val state = BridgeSettings.getInstance().snapshot()
        enabled.isSelected = state.enabled
        listenerPort.text = state.listenerPort.toString()
        setTableProfiles(uiProfiles(state))
        populateInterfaces(state.selectedAddresses.toSet(), state.selectedInterfaceNames.toSet())
        populateDistributionsAsync(state.wslDistro)
        updateStatus()
    }

    override fun disposeUIResources() {
        root = null
        distributionLoadGeneration++
        distributionsLoading = false
        interfaceChecks.clear()
    }

    private fun populateInterfaces(selectedAddresses: Set<String>, selectedInterfaceNames: Set<String> = emptySet()) {
        interfacePanel.removeAll()
        interfaceChecks.clear()
        interfaceNamesByAddress.clear()
        NetworkInterfaces.availableIpv4Addresses().forEach { item ->
            JBCheckBox(
                item.label,
                item.interfaceName in selectedInterfaceNames ||
                    item.address in selectedAddresses ||
                    (selectedAddresses.isEmpty() && selectedInterfaceNames.isEmpty() && item.suggestedForWsl),
            ).also { check ->
                interfaceChecks[item.address] = check
                interfaceNamesByAddress[item.address] = item.interfaceName
                interfacePanel.add(check)
            }
        }
        interfacePanel.revalidate()
        interfacePanel.repaint()
    }

    private fun selectedAddresses(): List<String> = interfaceChecks.filterValues { it.isSelected }.keys.toList()

    private fun selectedInterfaceNames(): List<String> = interfaceChecks
        .filterValues { it.isSelected }
        .keys
        .mapNotNull(interfaceNamesByAddress::get)
        .distinct()

    private fun storedInterfaceNames(state: BridgeSettings.State): Set<String> =
        state.selectedInterfaceNames.toSet().ifEmpty {
            state.selectedAddresses
                .mapNotNull(interfaceNamesByAddress::get)
                .toSet()
        }

    private fun clientConfigurationPanel(): JComponent {
        val panel = JPanel(BorderLayout(6, 6)).apply {
            border = BorderFactory.createTitledBorder("WSL Client Configuration")
        }
        val controls = JPanel().apply {
            add(JBLabel("WSL distro:"))
            add(distro)
            add(JButton("Refresh distros").apply {
                addActionListener { populateDistributionsAsync(distro.selectedItem as? String ?: "") }
            })
            add(clientEndpoint)
        }
        val tabs = JTabbedPane()
        tabs.preferredSize = java.awt.Dimension(860, 135)
        tabs.addTab("Codex", clientActionPanel("Codex", "Add or update '${WslClientConfigurator.SERVER_NAME}' in ~/.codex/config.toml.") {
            applyWslConfiguration("Codex", "codex") { selectedDistro, _ -> configureAllClientRoutes(selectedDistro, "codex") }
        })
        tabs.addTab("Claude Code", clientActionPanel("Claude Code", "Add or update a user-scoped '${WslClientConfigurator.SERVER_NAME}' MCP server.") {
            applyWslConfiguration("Claude Code", "claude") { selectedDistro, _ -> configureAllClientRoutes(selectedDistro, "claude") }
        })
        tabs.addTab("GitHub Copilot CLI", clientActionPanel("GitHub Copilot CLI", "Add or update '${WslClientConfigurator.SERVER_NAME}' in GitHub Copilot CLI.") {
            applyWslConfiguration("GitHub Copilot CLI", "copilot") { selectedDistro, _ -> configureAllClientRoutes(selectedDistro, "copilot") }
        })
        tabs.addTab("Others", JPanel(BorderLayout(4, 4)).apply {
            add(JBLabel("Generic streamable HTTP MCP JSON for all configured servers:"), BorderLayout.NORTH)
            add(JScrollPane(genericConfig), BorderLayout.CENTER)
            add(JButton("Copy generic JSON").apply {
                addActionListener {
                    runCatching { endpoint() }
                        .onSuccess { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(WslClientConfigurator.genericJson(it)), null) }
                        .onFailure { Messages.showErrorDialog(it.message ?: "Bridge is not listening.", "MCP WSL Bridge") }
                }
            }, BorderLayout.SOUTH)
        })
        panel.add(controls, BorderLayout.NORTH)
        panel.add(tabs, BorderLayout.CENTER)
        panel.preferredSize = java.awt.Dimension(900, 190)
        return panel
    }

    private fun clientActionPanel(clientName: String, description: String, action: () -> Unit): JComponent = JPanel(BorderLayout(4, 4)).apply {
        add(JBLabel(description), BorderLayout.NORTH)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(JButton("Apply to WSL").apply { addActionListener { action() } })
            add(JButton("Remove from WSL").apply {
                addActionListener { removeWslConfiguration(clientName, clientKeyFor(clientName)) }
            })
        }, BorderLayout.SOUTH)
    }

    private fun clientKeyFor(clientName: String): String = when (clientName) {
        "Codex" -> "codex"
        "Claude Code" -> "claude"
        else -> "copilot"
    }

    private fun removeWslConfiguration(clientName: String, clientKey: String) {
        val selectedDistro = distro.selectedItem as? String
        if (selectedDistro.isNullOrBlank()) {
            Messages.showErrorDialog("Choose a WSL distribution first.", "MCP WSL Bridge")
            return
        }
        val confirmation = Messages.showYesNoDialog(
            "Remove all MCP WSL Bridge servers configured by this plugin from '$selectedDistro' for $clientName?",
            "Remove from WSL",
            Messages.getQuestionIcon(),
        )
        if (confirmation != Messages.YES) return

        status.text = "Removing MCP WSL Bridge servers from WSL '$selectedDistro'..."
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = removeAllClientRoutes(selectedDistro, clientKey)
            ApplicationManager.getApplication().invokeLater {
                if (result.succeeded) {
                    BridgeSettings.getInstance().snapshot().also { current ->
                        when (clientKey) {
                            "codex" -> {
                                current.configuredCodexDistros.remove(selectedDistro)
                                current.codexConfigured = current.configuredCodexDistros.isNotEmpty()
                            }
                            "claude" -> {
                                current.configuredClaudeDistros.remove(selectedDistro)
                                current.claudeConfigured = current.configuredClaudeDistros.isNotEmpty()
                            }
                            else -> current.configuredCopilotDistros.remove(selectedDistro)
                        }
                        BridgeSettings.getInstance().update(current)
                    }
                    status.text = "$clientName removed from WSL '$selectedDistro'."
                } else {
                    status.text = "$clientName removal from WSL '$selectedDistro' failed."
                    Messages.showErrorDialog(
                        result.output.ifBlank { "Removal command failed with exit code ${result.exitCode}." },
                        "MCP WSL Bridge",
                    )
                }
            }
        }
    }

    private fun populateDistributionsAsync(selected: String) {
        val generation = ++distributionLoadGeneration
        distributionsLoading = true
        distro.isEnabled = false
        clientEndpoint.text = "Loading WSL distributions..."
        ApplicationManager.getApplication().executeOnPooledThread {
            val distributions = WslClientConfigurator.distributions()
            ApplicationManager.getApplication().invokeLater {
                if (root == null || generation != distributionLoadGeneration) return@invokeLater
                distro.removeAllItems()
                distributions.forEach(distro::addItem)
                if (selected.isNotBlank()) distro.selectedItem = selected
                if (distro.selectedIndex < 0 && distro.itemCount > 0) distro.selectedIndex = 0
                distro.isEnabled = true
                distributionsLoading = false
                updateStatus()
            }
        }
    }

    private fun applyWslConfiguration(clientName: String, clientKey: String, action: (String, String) -> WslClientConfigurator.CommandResult) {
        val selectedDistro = distro.selectedItem as? String
        val bridgeEndpoint = runCatching { httpEndpoint() }.getOrElse { error ->
            Messages.showErrorDialog(error.message ?: "Bridge is not listening.", "MCP WSL Bridge")
            return
        }
        if (selectedDistro.isNullOrBlank()) {
            Messages.showErrorDialog("Choose a WSL distribution first.", "MCP WSL Bridge")
            return
        }
        BridgeSettings.getInstance().snapshot().also { current ->
            current.wslDistro = selectedDistro
            BridgeSettings.getInstance().update(current)
        }
        status.text = "Configuring $clientName in WSL '$selectedDistro'..."
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { action(selectedDistro, bridgeEndpoint) }
                .getOrElse { error ->
                    WslClientConfigurator.CommandResult(
                        1,
                        error.message ?: "Unexpected ${error.javaClass.simpleName} while configuring $clientName.",
                    )
                }
            ApplicationManager.getApplication().invokeLater {
                if (result.succeeded) {
                    BridgeSettings.getInstance().snapshot().also { current ->
                        if (clientKey == "codex") {
                            current.codexConfigured = true
                            if (selectedDistro !in current.configuredCodexDistros) current.configuredCodexDistros.add(selectedDistro)
                        } else if (clientKey == "claude") {
                            current.claudeConfigured = true
                            if (selectedDistro !in current.configuredClaudeDistros) current.configuredClaudeDistros.add(selectedDistro)
                        } else {
                            if (selectedDistro !in current.configuredCopilotDistros) current.configuredCopilotDistros.add(selectedDistro)
                        }
                        BridgeSettings.getInstance().update(current)
                    }
                    status.text = "$clientName configured in WSL '$selectedDistro': $bridgeEndpoint"
                } else {
                    val message = result.output.ifBlank { "Configuration command failed with exit code ${result.exitCode}." }
                    status.text = "$clientName configuration failed in WSL '$selectedDistro'."
                    Messages.showErrorDialog(message, "MCP WSL Bridge")
                }
            }
        }
    }

    private fun configureAllClientRoutes(distro: String, client: String): WslClientConfigurator.CommandResult {
        val current = McpBridgeService.getInstance().status()
        val address = current.runningAddresses.firstOrNull()
            ?: return WslClientConfigurator.CommandResult(1, current.error ?: "Bridge is not listening.")
        val base = "http://$address:${current.listenerPort}"
        for (route in current.routes.filter { it.target != null }) {
            val serverName = if (route.id == "intellij") WslClientConfigurator.SERVER_NAME else route.id
            val endpoint = "$base${route.publicPath}"
            val result = when (client) {
                "codex" -> WslClientConfigurator.configureCodex(distro, endpoint, serverName)
                "claude" -> WslClientConfigurator.configureClaudeCode(distro, endpoint, serverName)
                else -> WslClientConfigurator.configureCopilotCli(distro, endpoint, serverName)
            }
            if (!result.succeeded) return result
        }
        return WslClientConfigurator.CommandResult(0, "Configured ${current.routes.size} MCP routes.")
    }

    private fun removeAllClientRoutes(distro: String, client: String): WslClientConfigurator.CommandResult {
        val current = McpBridgeService.getInstance().status()
        val routeNames = current.routes.map { route -> if (route.id == "intellij") WslClientConfigurator.SERVER_NAME else route.id }
        var firstFailure: WslClientConfigurator.CommandResult? = null
        routeNames.forEach { serverName ->
            val result = when (client) {
                "codex" -> WslClientConfigurator.removeCodex(distro, serverName)
                "claude" -> WslClientConfigurator.removeClaudeCode(distro, serverName)
                else -> WslClientConfigurator.removeCopilotCli(distro, serverName)
            }
            if (!result.succeeded && firstFailure == null) firstFailure = result
        }
        return firstFailure ?: WslClientConfigurator.CommandResult(0, "Removed ${routeNames.size} MCP routes.")
    }

    private fun endpoint(): String {
        val bridge = McpBridgeService.getInstance().status()
        val address = bridge.runningAddresses.firstOrNull()
            ?: throw IllegalStateException(bridge.error ?: "Enable the bridge and select a network interface first.")
        val path = bridge.routes.firstOrNull()?.publicPath ?: "/stream"
        return "http://$address:${BridgeSettings.getInstance().snapshot().listenerPort}$path"
    }

    private fun httpEndpoint(): String {
        val bridge = McpBridgeService.getInstance().status()
        val address = bridge.runningAddresses.firstOrNull()
            ?: throw IllegalStateException(bridge.error ?: "Enable the bridge and select a network interface first.")
        val path = bridge.routes.firstOrNull()?.publicPath ?: "/stream"
        return "http://$address:${BridgeSettings.getInstance().snapshot().listenerPort}$path"
    }

    private fun uiProfiles(state: BridgeSettings.State): List<BridgeSettings.ServerProfile> =
        listOf(intellijProfile()) + state.servers.filter { it.id != "intellij" }

    private fun setTableProfiles(profiles: List<BridgeSettings.ServerProfile>) {
        serverTableModel.setRowCount(0)
        profiles.forEach { profile ->
            serverTableModel.addRow(arrayOf<Any?>(
                profile.enabled,
                profile.id,
                profile.displayName,
                if (profile.serverType == BridgeSettings.ServerType.INTELLIJ_BUILT_IN) "intellij" else "http",
                profile.publicPath,
                profile.targetHost,
                profile.targetPort.toString(),
            ))
        }
    }

    private fun tableProfiles(): List<BridgeSettings.ServerProfile> {
        if (serverTable.isEditing) serverTable.cellEditor.stopCellEditing()
        val profiles = (0 until serverTableModel.rowCount).map { row ->
            fun value(column: Int): String = serverTableModel.getValueAt(row, column)?.toString()?.trim().orEmpty()
            val line = row + 1
            val id = value(1)
            require(id.matches(Regex("[A-Za-z0-9._-]+"))) { "Server row $line has an invalid ID." }
            val type = when (value(3).lowercase()) {
                "intellij", "built-in", "builtin" -> BridgeSettings.ServerType.INTELLIJ_BUILT_IN
                "http" -> BridgeSettings.ServerType.HTTP
                else -> throw IllegalArgumentException("Server row $line type must be 'intellij' or 'http'.")
            }
            val port = value(6).toIntOrNull()?.takeIf { it in 1..65535 }
                ?: throw IllegalArgumentException("Server row $line has an invalid port.")
            if (id == "intellij") return@map intellijProfile()
            BridgeSettings.ServerProfile(
                id = id,
                displayName = value(2).ifBlank { id },
                enabled = serverTableModel.getValueAt(row, 0) == true,
                publicPath = normalizeMcpPath(value(4)),
                targetHost = value(5).also { require(it.isNotEmpty()) { "Server row $line target host is empty." } },
                targetPort = port,
                targetPath = normalizeMcpPath(value(4)),
                targetMode = BridgeSettings.TargetMode.MANUAL,
                serverType = type,
            )
        }
        require(profiles.isNotEmpty()) { "Add at least one MCP server." }
        require(profiles.map { it.id }.distinct().size == profiles.size) { "MCP server IDs must be unique." }
        require(profiles.map { it.publicPath }.distinct().size == profiles.size) { "MCP public paths must be unique." }
        return profiles
    }

    private fun updateStatus() {
        val current = McpBridgeService.getInstance().status()
        clientEndpoint.text = runCatching { "WSL HTTP endpoint: ${httpEndpoint()}" }.getOrElse { "WSL endpoint: start the bridge first" }
        genericConfig.text = runCatching {
            val address = current.runningAddresses.firstOrNull() ?: error("Bridge is not listening")
            val base = "http://$address:${current.listenerPort}"
            WslClientConfigurator.genericJson(current.routes.associate { route ->
                val name = if (route.id == "intellij") WslClientConfigurator.SERVER_NAME else route.id
                name to "$base${route.publicPath}"
            })
        }.getOrDefault("Start the bridge to generate a configuration.")
        status.text = when {
            current.error != null -> "Status: ${current.error}"
            current.runningAddresses.isNotEmpty() -> "Status: listening on ${current.runningAddresses.joinToString()} → ${current.target?.host}:${current.target?.port}"
            else -> "Status: stopped"
        }
    }

    private fun addPreset(profile: BridgeSettings.ServerProfile) {
        if ((0 until serverTableModel.rowCount).any { serverTableModel.getValueAt(it, ID_COLUMN) == profile.id }) {
            Messages.showInfoMessage("${profile.displayName} is already in the server list.", "MCP WSL Bridge")
            return
        }
        serverTableModel.addRow(arrayOf<Any?>(
            profile.enabled,
            profile.id,
            profile.displayName,
            if (profile.serverType == BridgeSettings.ServerType.INTELLIJ_BUILT_IN) "intellij" else "http",
            profile.publicPath,
            profile.targetHost,
            profile.targetPort.toString(),
        ))
    }

    private fun intellijProfile() = BridgeSettings.ServerProfile(
        id = "intellij",
        displayName = "IntelliJ MCP",
        publicPath = "/stream",
        targetHost = "127.0.0.1",
        targetPort = BridgeSettings.DEFAULT_MCP_PORT,
        targetPath = "/stream",
        targetMode = BridgeSettings.TargetMode.AUTO,
        serverType = BridgeSettings.ServerType.INTELLIJ_BUILT_IN,
    )

    private fun indexProfile() = BridgeSettings.ServerProfile(
        id = "intellij-index",
        displayName = "IDE Index MCP",
        publicPath = "/index-mcp/streamable-http",
        targetHost = "127.0.0.1",
        targetPort = 29170,
        targetPath = "/index-mcp/streamable-http",
        targetMode = BridgeSettings.TargetMode.MANUAL,
        serverType = BridgeSettings.ServerType.HTTP,
    )

    private companion object {
        val SERVER_COLUMNS = arrayOf(
            "Enabled", "ID", "Name", "Type", "MCP path", "Target host", "Port",
        )
        const val ID_COLUMN = 1
        const val PUBLIC_PATH_COLUMN = 4
    }
}

private class McpServerDialog(existing: BridgeSettings.ServerProfile?) : DialogWrapper(null) {
    private val id = JBTextField(existing?.id ?: "custom-mcp")
    private val displayName = JBTextField(existing?.displayName ?: "Custom MCP")
    private val publicPath = JBTextField(existing?.publicPath ?: "/mcp/custom-mcp")
    private val targetHost = JBTextField(existing?.targetHost ?: "127.0.0.1")
    private val targetPort = JBTextField(existing?.targetPort?.takeIf { it > 0 }?.toString() ?: "")
    private val enabled = JBCheckBox("Enabled", existing?.enabled ?: true)

    init {
        title = if (existing == null) "Add MCP Server" else "Edit MCP Server"
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(GridBagLayout()).apply {
        val constraints = GridBagConstraints().apply {
            insets = Insets(4, 4, 4, 4)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
        }
        fun addField(label: String, field: JComponent, row: Int) {
            constraints.gridx = 0; constraints.gridy = row; constraints.weightx = 0.0
            add(JBLabel(label), constraints)
            constraints.gridx = 1; constraints.weightx = 1.0
            add(field, constraints)
        }
        addField("ID:", id, 0)
        addField("Name:", displayName, 1)
        addField("MCP path:", publicPath, 2)
        addField("Target host:", targetHost, 3)
        addField("Target port:", targetPort, 4)
        constraints.gridx = 1; constraints.gridy = 5; constraints.weightx = 1.0
        add(enabled, constraints)
        preferredSize = java.awt.Dimension(430, 220)
    }

    override fun doValidate(): ValidationInfo? {
        val idValue = id.text.trim()
        if (!idValue.matches(Regex("[A-Za-z0-9._-]+"))) {
            return ValidationInfo("Use letters, numbers, dots, underscores, or hyphens for the ID.", id)
        }
        if (publicPath.text.trim().isBlank() || !publicPath.text.trim().startsWith('/')) {
            return ValidationInfo("MCP path must start with '/'.", publicPath)
        }
        if (targetHost.text.trim().isBlank()) return ValidationInfo("Target host is required.", targetHost)
        if (targetPort.text.toIntOrNull() !in 1..65535) {
            return ValidationInfo("Target port must be between 1 and 65535.", targetPort)
        }
        return null
    }

    fun profile(): BridgeSettings.ServerProfile {
        val path = publicPath.text.trim().let { if (it.startsWith('/')) it else "/$it" }
        return BridgeSettings.ServerProfile(
            id = id.text.trim(),
            displayName = displayName.text.trim().ifBlank { id.text.trim() },
            enabled = enabled.isSelected,
            publicPath = path,
            targetHost = targetHost.text.trim(),
            targetPort = targetPort.text.trim().toInt(),
            targetPath = path,
            targetMode = BridgeSettings.TargetMode.MANUAL,
            serverType = BridgeSettings.ServerType.HTTP,
        )
    }
}
