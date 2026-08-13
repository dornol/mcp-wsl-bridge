package io.github.dornol.mcpwslbridge

import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object WslClientConfigurator {
    /** Kept for compatibility with clients that used the old fixed name. */
    const val SERVER_NAME = "intellij-wsl-bridge"

    fun defaultServerName(): String {
        val product = runCatching { ApplicationNamesInfo.getInstance().productName }
            .getOrDefault("IntelliJ IDEA")
        val productId = when {
            product.contains("RustRover", ignoreCase = true) -> "rustrover"
            product.contains("IntelliJ", ignoreCase = true) || product.contains("IDEA", ignoreCase = true) -> "intellij"
            else -> product.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        }
        return "${productId.ifBlank { "ide" }}-wsl-bridge"
    }

    fun serverNameForRoute(routeId: String): String =
        if (routeId == "intellij") defaultServerName() else routeId

    data class CommandResult(val exitCode: Int, val output: String) {
        val succeeded: Boolean get() = exitCode == 0
    }

    internal var commandRunner: (List<String>) -> CommandResult = ::execute
    private val loginShells = ConcurrentHashMap<String, String>()
    @Volatile private var cachedDistributions: List<String>? = null
    @Volatile private var distributionsCachedAt: Long = 0

    fun distributions(forceRefresh: Boolean = false): List<String> {
        if (!SystemInfo.isWindows) return emptyList()
        val now = System.currentTimeMillis()
        cachedDistributions?.takeIf { !forceRefresh && now - distributionsCachedAt < DISTRIBUTION_CACHE_MILLIS }?.let { return it }
        val result = commandRunner(listOf("wsl.exe", "-l", "-q"))
        val distributions = if (result.succeeded) parseDistributions(result.output) else emptyList()
        cachedDistributions = distributions
        distributionsCachedAt = now
        return distributions
    }

    fun refreshDistributions(): List<String> = distributions(forceRefresh = true)

    internal fun clearCaches() {
        loginShells.clear()
        cachedDistributions = null
        distributionsCachedAt = 0
    }

    fun configureCodex(distro: String, endpoint: String): CommandResult {
        return configureCodex(distro, endpoint, defaultServerName())
    }

    fun configureClaudeCode(distro: String, endpoint: String): CommandResult {
        return configureClaudeCode(distro, endpoint, defaultServerName())
    }

    fun configureCodex(distro: String, endpoint: String, serverName: String): CommandResult {
        runInWsl(distro, listOf("codex", "mcp", "remove", serverName))
        return runInWsl(distro, listOf("codex", "mcp", "add", serverName, "--url", endpoint))
    }

    fun configureClaudeCode(distro: String, endpoint: String, serverName: String): CommandResult {
        runInWsl(distro, listOf("claude", "mcp", "remove", "--scope", "user", serverName))
        return runInWsl(
            distro,
            listOf("claude", "mcp", "add", "--scope", "user", "--transport", "http", serverName, endpoint),
        )
    }

    fun configureCopilotCli(distro: String, endpoint: String): CommandResult {
        return configureCopilotCli(distro, endpoint, defaultServerName())
    }

    fun configureCopilotCli(distro: String, endpoint: String, serverName: String): CommandResult {
        runInWsl(distro, listOf("copilot", "mcp", "remove", serverName))
        return runInWsl(
            distro,
            listOf("copilot", "mcp", "add", "--transport", "http", serverName, endpoint),
        )
    }

    fun isCommandAvailable(distro: String, command: String): Boolean =
        runInWsl(distro, listOf("command", "-v", command)).succeeded

    fun removeCodex(distro: String, serverName: String): CommandResult =
        runInWsl(distro, listOf("codex", "mcp", "remove", serverName))

    fun removeClaudeCode(distro: String, serverName: String): CommandResult =
        runInWsl(distro, listOf("claude", "mcp", "remove", "--scope", "user", serverName))

    fun removeCopilotCli(distro: String, serverName: String): CommandResult =
        runInWsl(distro, listOf("copilot", "mcp", "remove", serverName))

    fun genericJson(endpoint: String): String = """
        {
          "mcpServers": {
            "${defaultServerName()}": {
              "url": "$endpoint"
            }
          }
        }
    """.trimIndent()

    fun genericJson(endpoints: Map<String, String>): String {
        val entries = endpoints.entries.joinToString(",\n") { (name, endpoint) ->
            "    \"${jsonEscape(name)}\": {\n      \"url\": \"${jsonEscape(endpoint)}\"\n    }"
        }
        return "{\n  \"mcpServers\": {\n$entries\n  }\n}"
    }

    private fun jsonEscape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

    private fun runInWsl(distro: String, command: List<String>): CommandResult {
        require(distro.isNotBlank()) { "Choose a WSL distribution first." }
        val shell = loginShellFor(distro)
        val shellCommand = command.joinToString(" ") { shellQuote(it) }
        // Do not start an interactive shell here. Interactive zsh/bash startup files can launch
        // prompts, tmux, plugin updaters, or other terminal-only commands and never return when
        // invoked by an IDE process. A login shell is sufficient for the user's configured PATH.
        return commandRunner(listOf("wsl.exe", "-d", distro, "--", shell, "-lc", shellCommand))
    }

    private fun loginShellFor(distro: String): String {
        return loginShells.computeIfAbsent(distro) {
            val result = commandRunner(listOf("wsl.exe", "-d", distro, "--", "sh", "-lc", "getent passwd \"$(id -u)\" | cut -d: -f7"))
            result.output.lineSequence().firstOrNull { it.startsWith('/') } ?: "/bin/sh"
        }
    }

    /**
     * IntelliJ's local MCP server has additional loopback protections beyond the HTTP Host header.
     * Claude Code reaches the Windows host through the WSL gateway, so give it a WSL-loopback
     * endpoint and relay that one connection to the Windows bridge. This also survives Claude's
     * strict HTTP transport checks.
     */
    fun ensureLoopbackProxy(distro: String, endpoint: String): String? {
        val encodedScript = Base64.getEncoder().encodeToString(LOOPBACK_PROXY_SCRIPT.toByteArray(StandardCharsets.UTF_8))
        val install = runInWsl(
            distro,
            listOf(
                "sh", "-lc",
                "mkdir -p \"$PROXY_DIRECTORY\" && printf %s '$encodedScript' | base64 -d > \"$PROXY_SCRIPT\" && chmod 700 \"$PROXY_SCRIPT\"",
            ),
        )
        if (!install.succeeded) return null

        val start = runInWsl(
            distro,
            listOf(
                "sh", "-lc",
                "nohup node \"$PROXY_SCRIPT\" '$endpoint' $LOOPBACK_PROXY_PORT > \"$PROXY_LOG\" 2>&1 &",
            ),
        )
        return if (start.succeeded) "http://127.0.0.1:$LOOPBACK_PROXY_PORT/stream" else null
    }

    internal fun parseDistributions(output: String): List<String> = output.lineSequence()
        .map { it.replace("\u0000", "").trim() }
        .filter(String::isNotEmpty)
        .toList()

    internal fun shellQuote(value: String): String = "'${value.replace("'", "'\\\"'\\\"'")}'"

    private fun execute(command: List<String>): CommandResult {
        if (!SystemInfo.isWindows) return CommandResult(1, "WSL auto-configuration is available only when a JetBrains IDE runs on Windows.")
        return runCatching {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .directory(File(System.getProperty("user.home")))
                .start()
            val output = CompletableFuture.supplyAsync { process.inputStream.readBytes() }
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor()
                CommandResult(
                    TIMEOUT_EXIT_CODE,
                    "WSL configuration command timed out after $COMMAND_TIMEOUT_SECONDS seconds.",
                )
            } else {
                CommandResult(process.exitValue(), decodeProcessOutput(output.get()))
            }
        }.getOrElse { error -> CommandResult(1, error.message ?: error.javaClass.simpleName) }
    }

    /** wsl.exe may write UTF-16LE when launched by a Windows GUI process. */
    internal fun decodeProcessOutput(bytes: ByteArray): String {
        val oddNullBytes = bytes.indices.count { index -> index % 2 == 1 && bytes[index].toInt() == 0 }
        val charset = if (bytes.size >= 2 && oddNullBytes * 2 >= bytes.size / 2) StandardCharsets.UTF_16LE else StandardCharsets.UTF_8
        return String(bytes, charset).replace("\u0000", "").trim()
    }

    private const val LOOPBACK_PROXY_PORT = 64344
    private const val DISTRIBUTION_CACHE_MILLIS = 5_000L
    private const val COMMAND_TIMEOUT_SECONDS = 30L
    private const val TIMEOUT_EXIT_CODE = 124
    private const val PROXY_DIRECTORY = "\$HOME/.local/share/mcp-wsl-bridge"
    private const val PROXY_SCRIPT = "\$HOME/.local/share/mcp-wsl-bridge/loopback-proxy.js"
    private const val PROXY_LOG = "\$HOME/.local/share/mcp-wsl-bridge/loopback-proxy.log"

    private val LOOPBACK_PROXY_SCRIPT = """
        const http = require('http');
        const { URL } = require('url');

        const target = new URL(process.argv[2]);
        const port = Number(process.argv[3]);
        const targetPort = target.port || (target.protocol === 'https:' ? 443 : 80);
        const gateway = () => require('child_process')
          .execFileSync('sh', ['-lc', "ip route show default | awk '{print $3; exit}'"], { encoding: 'utf8' })
          .trim();

        const server = http.createServer((request, response) => {
          let hostname;
          try {
            hostname = gateway();
          } catch (error) {
            console.error('Unable to resolve the Windows gateway: ' + error.message);
            response.writeHead(502);
            response.end();
            return;
          }
          if (!hostname) {
            response.writeHead(502);
            response.end();
            return;
          }
          // Match the origin used by a client running directly beside IntelliJ. Keep
          // MCP session and SSE headers untouched so approval requests can travel back
          // through the same Streamable HTTP session.
          const headers = {
            ...request.headers,
            host: `127.0.0.1:${'$'}{targetPort}`,
            origin: target.origin,
          };
          const upstream = http.request({
            hostname,
            port: targetPort,
            path: request.url,
            method: request.method,
            headers,
          }, (upstreamResponse) => {
            response.writeHead(upstreamResponse.statusCode || 502, upstreamResponse.headers);
            upstreamResponse.pipe(response);
          });
          upstream.on('error', (error) => {
            console.error(error.message);
            response.writeHead(502);
            response.end();
          });
          request.pipe(upstream);
        });

        server.listen(port, '127.0.0.1');
    """.trimIndent()
}
