package io.github.dornol.mcpwslbridge

import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.nio.charset.StandardCharsets

object WslClientConfigurator {
    const val SERVER_NAME = "intellij-wsl-bridge"

    data class CommandResult(val exitCode: Int, val output: String) {
        val succeeded: Boolean get() = exitCode == 0
    }

    fun distributions(): List<String> {
        if (!SystemInfo.isWindows) return emptyList()
        val result = execute(listOf("wsl.exe", "-l", "-q"))
        return if (result.succeeded) result.output.lineSequence().map(String::trim).filter(String::isNotEmpty).toList() else emptyList()
    }

    fun configureCodex(distro: String, endpoint: String): CommandResult {
        runInWsl(distro, listOf("codex", "mcp", "remove", SERVER_NAME))
        return runInWsl(distro, listOf("codex", "mcp", "add", SERVER_NAME, "--url", endpoint))
    }

    fun configureClaudeCode(distro: String, endpoint: String): CommandResult {
        runInWsl(distro, listOf("claude", "mcp", "remove", "--scope", "user", SERVER_NAME))
        return runInWsl(
            distro,
            listOf("claude", "mcp", "add", "--scope", "user", "--transport", "http", SERVER_NAME, endpoint),
        )
    }

    fun genericJson(endpoint: String): String = """
        {
          "mcpServers": {
            "$SERVER_NAME": {
              "url": "$endpoint"
            }
          }
        }
    """.trimIndent()

    private fun runInWsl(distro: String, command: List<String>): CommandResult {
        require(distro.isNotBlank()) { "Choose a WSL distribution first." }
        return execute(listOf("wsl.exe", "-d", distro, "--") + command)
    }

    private fun execute(command: List<String>): CommandResult {
        if (!SystemInfo.isWindows) return CommandResult(1, "WSL auto-configuration is available only when IntelliJ runs on Windows.")
        return runCatching {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .directory(File(System.getProperty("user.home")))
                .start()
            val output = process.inputStream.readBytes().toString(StandardCharsets.UTF_8).trim()
            CommandResult(process.waitFor(), output)
        }.getOrElse { error -> CommandResult(1, error.message ?: error.javaClass.simpleName) }
    }
}
