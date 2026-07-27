package io.github.dornol.mcpwslbridge

import com.intellij.ide.AppLifecycleListener

/** Instantiates the application service when IntelliJ creates its application frame. */
class McpBridgeAppLifecycleListener : AppLifecycleListener {
    override fun appFrameCreated(commandLineArgs: List<String>) {
        McpBridgeService.getInstance()
    }
}
