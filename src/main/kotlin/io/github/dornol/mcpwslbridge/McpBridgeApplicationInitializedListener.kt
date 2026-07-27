package io.github.dornol.mcpwslbridge

import com.intellij.ide.ApplicationInitializedListener

/** Instantiates the application service after IntelliJ has finished initializing. */
class McpBridgeApplicationInitializedListener : ApplicationInitializedListener {
    override suspend fun execute() {
        McpBridgeService.getInstance()
    }
}
