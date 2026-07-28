package io.github.dornol.mcpwslbridge

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

class McpBridgeStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = McpBridgeStatusBarWidget.ID

    override fun getDisplayName(): String = "MCP WSL Bridge Status"

    override fun createWidget(project: Project): StatusBarWidget = McpBridgeStatusBarWidget(project)
}
