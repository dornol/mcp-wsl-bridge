package io.github.dornol.mcpwslbridge

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ide.DataManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidget.IconPresentation
import com.intellij.openapi.wm.StatusBarWidget.WidgetPresentation
import com.intellij.openapi.util.IconLoader
import com.intellij.util.Consumer
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseEvent
import java.awt.Point
import javax.swing.Icon

class McpBridgeStatusBarWidget(private val project: Project) : StatusBarWidget, Disposable {
    private var statusBar: StatusBar? = null
    private var status = McpBridgeService.Status(
        runningAddresses = emptyList(),
        target = null,
        error = null,
        state = McpBridgeService.State.DISABLED,
        listenerPort = BridgeSettings.DEFAULT_LISTENER_PORT,
        lastSuccessfulRefreshTime = null,
    )
    private var statusSubscription: Disposable? = null

    override fun ID(): String = ID

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        statusSubscription = McpBridgeService.getInstance().addStatusListener { next ->
            status = next
            ApplicationManager.getApplication().invokeLater {
                this.statusBar?.updateWidget(ID)
            }
        }
    }

    override fun getPresentation(): WidgetPresentation = object : IconPresentation {
        override fun getIcon(): Icon = iconFor(status.state)

        override fun getTooltipText(): String = tooltipFor(status)

        override fun getClickConsumer(): Consumer<MouseEvent> = Consumer { showPopup(it.component) }
    }

    override fun dispose() {
        statusSubscription?.dispose()
        statusSubscription = null
        statusBar = null
    }

    private fun showPopup(anchor: java.awt.Component) {
        val group = com.intellij.openapi.actionSystem.DefaultActionGroup()
        group.add(object : AnAction(tooltipFor(status)) {
            override fun actionPerformed(event: AnActionEvent) = Unit

            override fun update(event: AnActionEvent) {
                event.presentation.isEnabled = false
            }
        })
        group.addSeparator()
        group.add(object : AnAction("Restart Bridge") {
            override fun actionPerformed(event: AnActionEvent) {
                McpBridgeService.getInstance().restart()
            }
        })
        group.add(object : AnAction("Open Settings") {
            override fun actionPerformed(event: AnActionEvent) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, "MCP WSL Bridge")
            }
        })
        if (status.state == McpBridgeService.State.CONNECTED) {
            group.add(object : AnAction("Copy Endpoint") {
                override fun actionPerformed(event: AnActionEvent) {
                    val address = status.runningAddresses.firstOrNull() ?: return
                    val endpoint = "http://$address:${status.listenerPort}/stream"
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(endpoint), null)
                }
            })
        }
        val popup = JBPopupFactory.getInstance().createActionGroupPopup(
            "MCP WSL Bridge",
            group,
            DataManager.getInstance().getDataContext(anchor),
            JBPopupFactory.ActionSelectionAid.MNEMONICS,
            true,
        )
        popup.pack(true, true)
        val anchorLocation = anchor.locationOnScreen
        popup.showInScreenCoordinates(
            anchor,
            Point(anchorLocation.x, anchorLocation.y - popup.size.height - 4),
        )
    }

    private fun tooltipFor(status: McpBridgeService.Status): String = buildString {
        append("MCP WSL Bridge — ")
        append(status.state.name.lowercase().replaceFirstChar { it.uppercase() })
        if (status.state == McpBridgeService.State.CONNECTED) {
            val address = status.runningAddresses.firstOrNull()
            if (address != null) append(" — http://$address:${status.listenerPort}/stream")
        }
        if (status.error != null && status.state == McpBridgeService.State.ERROR) append(" — ${status.error}")
    }

    private fun iconFor(state: McpBridgeService.State): Icon = IconLoader.findIcon(
        when (state) {
            McpBridgeService.State.DISABLED -> "/icons/mcp-bridge-disabled.svg"
            McpBridgeService.State.STARTING -> "/icons/mcp-bridge-starting.svg"
            McpBridgeService.State.CONNECTED -> "/icons/mcp-bridge-connected.svg"
            McpBridgeService.State.ERROR -> "/icons/mcp-bridge-error.svg"
        },
        McpBridgeStatusBarWidget::class.java.classLoader,
    ) ?: error("MCP WSL Bridge status icon is missing")

    companion object {
        const val ID = "MCP WSL Bridge Status"
    }
}
