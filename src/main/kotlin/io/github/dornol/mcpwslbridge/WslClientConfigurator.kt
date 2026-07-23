package io.github.dornol.mcpwslbridge

import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64

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
        val localEndpoint = startLoopbackProxy(distro, endpoint) ?: return CommandResult(1, "Could not start the WSL loopback proxy.")
        runInWsl(distro, listOf("codex", "mcp", "remove", SERVER_NAME))
        return runInWsl(distro, listOf("codex", "mcp", "add", SERVER_NAME, "--url", localEndpoint))
    }

    fun configureClaudeCode(distro: String, endpoint: String): CommandResult {
        val localEndpoint = startLoopbackProxy(distro, endpoint) ?: return CommandResult(1, "Could not start the WSL loopback proxy.")
        runInWsl(distro, listOf("claude", "mcp", "remove", "--scope", "user", SERVER_NAME))
        return runInWsl(
            distro,
            listOf("claude", "mcp", "add", "--scope", "user", "--transport", "http", SERVER_NAME, localEndpoint),
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
        val shellCommand = command.joinToString(" ") { shellQuote(it) }
        return execute(listOf("wsl.exe", "-d", distro, "--", shell, "-lic", shellCommand))
    }

    private fun loginShellFor(distro: String): String {
        val result = execute(listOf("wsl.exe", "-d", distro, "--", "sh", "-lc", "getent passwd \"$(id -u)\" | cut -d: -f7"))
        return result.output.lineSequence().firstOrNull { it.startsWith('/') } ?: "/bin/sh"
    }

    /**
     * IntelliJ's local MCP server has additional loopback protections beyond the HTTP Host header.
     * Claude Code reaches the Windows host through the WSL gateway, so give it a WSL-loopback
     * endpoint and relay that one connection to the Windows bridge. This also survives Claude's
     * strict HTTP transport checks.
     */
    private fun startLoopbackProxy(distro: String, endpoint: String): String? {
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

    private const val LOOPBACK_PROXY_PORT = 64344
    private const val PROXY_DIRECTORY = "\$HOME/.local/share/mcp-wsl-bridge"
    private const val PROXY_SCRIPT = "\$HOME/.local/share/mcp-wsl-bridge/loopback-proxy.js"
    private const val PROXY_LOG = "\$HOME/.local/share/mcp-wsl-bridge/loopback-proxy.log"

    private val LOOPBACK_PROXY_SCRIPT = """
        const http = require('http');
        const { URL } = require('url');

        const target = new URL(process.argv[2]);
        const port = Number(process.argv[3]);
        const targetPort = target.port || (target.protocol === 'https:' ? 443 : 80);

        const server = http.createServer((request, response) => {
          const headers = { ...request.headers, host: `127.0.0.1:${'$'}{targetPort}` };
          delete headers.origin;
          const upstream = http.request({
            hostname: target.hostname,
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
