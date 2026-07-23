package io.github.dornol.mcpwslbridge

import com.intellij.openapi.application.PathManager
import org.w3c.dom.Element
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

data class McpTarget(val host: String, val port: Int, val source: String)

class McpTargetResolver {
    fun resolve(settings: BridgeSettings.State): McpTarget? {
        if (settings.targetMode == BridgeSettings.TargetMode.MANUAL) {
            return McpTarget(settings.targetHost, settings.targetPort, "Manual setting")
        }

        configuredPort()?.let { return McpTarget("127.0.0.1", it, "IntelliJ MCP settings") }
        return findListeningPort()?.let { McpTarget("127.0.0.1", it, "Loopback port probe") }
    }

    private fun configuredPort(): Int? {
        val configFile = Path.of(PathManager.getOptionsPath(), "mcpServer.xml")
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

    private fun findListeningPort(): Int? = (BridgeSettings.DEFAULT_MCP_PORT..BridgeSettings.DEFAULT_MCP_PORT + 20)
        .firstOrNull { port ->
            runCatching {
                Socket().use { socket -> socket.connect(InetSocketAddress("127.0.0.1", port), 150) }
                true
            }.getOrDefault(false)
        }

    private fun isValidPort(port: Int) = port in 1..65535
}

