package io.github.dornol.mcpwslbridge

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.util.SystemInfo
import org.w3c.dom.Element
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.stream.IntStream
import javax.xml.parsers.DocumentBuilderFactory

data class McpTarget(val host: String, val port: Int, val source: String)

class McpTargetResolver(
    private val optionsPathProvider: () -> Path = { PathManager.getConfigDir().resolve("options") },
    private val portProbe: (Int) -> Boolean = ::isListening,
) {
    fun diagnostic(): String {
        val file = configFile()
        if (!Files.isRegularFile(file)) return "MCP settings file not found: $file"
        return if (configuredPort() != null) {
            "MCP settings file: $file"
        } else {
            "MCP settings file has no valid port: $file"
        }
    }

    fun resolve(profile: BridgeSettings.ServerProfile): McpTarget? {
        if (profile.targetMode == BridgeSettings.TargetMode.MANUAL) {
            return McpTarget(profile.targetHost, profile.targetPort, "Manual setting")
        }
        if (profile.serverType == BridgeSettings.ServerType.HTTP &&
            (profile.id == "intellij-index" || profile.targetPath == "/index-mcp/streamable-http")
        ) {
            return findListeningPort(profile.targetPort, profile.targetPort + PORT_SCAN_WINDOW)
                ?.let { McpTarget(profile.targetHost, it, "IDE Index MCP loopback probe") }
        }
        if (profile.serverType != BridgeSettings.ServerType.INTELLIJ_BUILT_IN) {
            return McpTarget(profile.targetHost, profile.targetPort, "Manual setting")
        }
        configuredPort()?.takeIf(portProbe)?.let { return McpTarget("127.0.0.1", it, "JetBrains MCP settings") }
        return findListeningPort(BridgeSettings.DEFAULT_MCP_PORT, MAX_MCP_PORT)
            ?.let { McpTarget("127.0.0.1", it, "Loopback port probe") }
    }

    fun resolve(settings: BridgeSettings.State): McpTarget? {
        if (settings.targetMode == BridgeSettings.TargetMode.MANUAL) {
            return McpTarget(settings.targetHost, settings.targetPort, "Manual setting")
        }

        configuredPort()?.takeIf(portProbe)?.let { return McpTarget("127.0.0.1", it, "JetBrains MCP settings") }
        return findListeningPort(BridgeSettings.DEFAULT_MCP_PORT, MAX_MCP_PORT)
            ?.let { McpTarget("127.0.0.1", it, "Loopback port probe") }
    }

    private fun configuredPort(): Int? {
        val configFile = configFile()
        if (!Files.isRegularFile(configFile)) return null

        return runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                isExpandEntityReferences = false
            }
            val document = factory.newDocumentBuilder().parse(configFile.toFile())
            val options = document.getElementsByTagName("option")
            (0 until options.length)
                .asSequence()
                .map { options.item(it) as? Element }
                .firstNotNullOfOrNull { element ->
                    val name = element?.getAttribute("name") ?: return@firstNotNullOfOrNull null
                    if (name.equals("port", ignoreCase = true) || name.contains("port", ignoreCase = true)) {
                        element.getAttribute("value").toIntOrNull()?.takeIf(::isValidPort)
                    } else null
                }
        }.getOrNull()
    }

    private fun configFile(): Path = optionsPathProvider().resolve("mcpServer.xml")

    /**
     * JetBrains IDEs do not all choose the same MCP port. In particular,
     * RustRover may allocate a port such as 64522 instead of staying close to
     * IntelliJ IDEA's usual 64342 range.
     */
    private fun findListeningPort(startPort: Int, endPort: Int): Int? {
        val lastPort = minOf(endPort, MAX_MCP_PORT)
        if (startPort > lastPort) return null

        listeningPortsFromNetstat(startPort, lastPort)?.let { return it.minOrNull() }

        // This is only a fallback for platforms without a usable netstat
        // listing. Probe concurrently so a missing MCP server does not block
        // the bridge refresh loop for one timeout per port.
        return IntStream.rangeClosed(startPort, lastPort)
            .parallel()
            .filter { portProbe(it) }
            .findFirst()
            .orElse(-1)
            .takeIf { it >= 0 }
    }

    private fun listeningPortsFromNetstat(startPort: Int, endPort: Int): Set<Int>? {
        if (!SystemInfo.isWindows) return null
        return runCatching {
            val process = ProcessBuilder("netstat", "-ano", "-p", "tcp")
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                null
            } else {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.mapNotNull { parseNetstatListeningPort(it, startPort, endPort) }.toSet()
                }
            }
        }.getOrNull()
    }

    private fun parseNetstatListeningPort(line: String, startPort: Int, endPort: Int): Int? {
        val fields = line.trim().split(Regex("\\s+"))
        if (fields.size < 4 || fields.none { it.equals("LISTENING", ignoreCase = true) }) return null
        val localAddress = fields.getOrNull(1) ?: return null
        val port = localAddress.substringAfterLast(':').toIntOrNull() ?: return null
        return port.takeIf { it in startPort..endPort }
    }

    private companion object {
        const val MAX_MCP_PORT = 65535
        const val PORT_SCAN_WINDOW = 20

        fun isListening(port: Int): Boolean = runCatching {
            Socket().use { socket -> socket.connect(InetSocketAddress("127.0.0.1", port), 150) }
            true
        }.getOrDefault(false)
    }

    private fun isValidPort(port: Int) = port in 1..65535
}
