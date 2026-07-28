package io.github.dornol.mcpwslbridge

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** Ensures the application bridge service is created after a project opens. */
class McpBridgeProjectStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        McpBridgeService.getInstance().restart()
    }
}
