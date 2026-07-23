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
        return if (result.succeeded) result.output.lineSequence()
            .map { it.replace("\u0000", "").trim() }
            .filter(String::isNotEmpty)
            .toList()
        else emptyList()
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
        val shell = loginShellFor(distro)
        val shellCommand = command.joinToString(" ")(::shellQuote)
        return execute(listOf("wsl.exe", "-d", distro, "--", shell, "-lic", shellCommand))
    }

    private fun loginShellFor(distro: String): String {
        val result = execute(listOf("wsl.exe", "-d", distro, "--", "sh", "-lc", "getent passwd \"$(id -u)\" | cut -d: -f7"))
        return result.output.lineSequence().firstOrNull { it.startsWith('/') } ?: "/bin/sh"
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\\"'\\\"'")}'"

    private fun execute(command: List<String>): CommandResult {
        if (!SystemInfo.isWindows) return CommandResult(1, "WSL auto-configuration is available only when IntelliJ runs on Windows.")
        return runCatching {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .directory(File(System.getProperty("user.home")))
                .start()
            val output = decodeProcessOutput(process.inputStream.readBytes())
            CommandResult(process.waitFor(), output)
        }.getOrElse { error -> CommandResult(1, error.message ?: error.javaClass.simpleName) }
    }

    /** wsl.exe may write UTF-16LE when launched by a Windows GUI process. */
    private fun decodeProcessOutput(bytes: ByteArray): String {
        val oddNullBytes = bytes.indices.count { index -> index % 2 == 1 && bytes[index].toInt() == 0 }
        val charset = if (bytes.size >= 2 && oddNullBytes * 2 >= bytes.size / 2) StandardCharsets.UTF_16LE else StandardCharsets.UTF_8
        return String(bytes, charset).replace("\u0000", "").trim()
    }
}
