package io.github.dornol.mcpwslbridge

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class PluginApiPolicyTest {
    @Test
    fun `production sources do not reintroduce known forbidden IntelliJ APIs`() {
        val sourceRoot = Path.of("src/main/kotlin")
        val forbiddenPatterns = listOf(
            "ApplicationInitializedListener",
            "appStarted()",
            "getOptionsPath()",
            "CopyPasteManager",
            "IconLoader.getIcon(",
            "preload=\"true\"",
        )
        val violations = mutableListOf<String>()
        Files.walk(sourceRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .forEach { path ->
                    val lines = Files.readAllLines(path)
                    forbiddenPatterns.forEach { pattern ->
                        if (lines.any { line -> line.contains(pattern) }) {
                            violations += "$path contains $pattern"
                        }
                    }
                }
        }

        assertTrue(violations.isEmpty(), violations.joinToString("\n"))
    }
}
