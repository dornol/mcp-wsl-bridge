package io.github.dornol.mcpwslbridge

import com.intellij.ide.AppLifecycleListener

/** Instantiates the application service once IntelliJ has started. */
class McpBridgeAppLifecycleListener : AppLifecycleListener {
    override fun appStarted() {
        McpBridgeService.getInstance()
    }
}
