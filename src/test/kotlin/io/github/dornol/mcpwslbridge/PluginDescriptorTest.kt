package io.github.dornol.mcpwslbridge

import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginDescriptorTest {
    @Test
    fun `plugin descriptor preloads bridge service and exposes configurable`() {
        val stream = requireNotNull(javaClass.classLoader.getResourceAsStream("META-INF/plugin.xml"))
        val document = stream.use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }
        val services = (0 until document.getElementsByTagName("applicationService").length)
            .map { document.getElementsByTagName("applicationService").item(it) as Element }

        val bridgeService = services.single { it.getAttribute("serviceImplementation").contains("McpBridgeService") }
        assertEquals("true", bridgeService.getAttribute("preload"))
        assertTrue(services.any { it.getAttribute("serviceImplementation").contains("BridgeSettings") })

        val configurables = (0 until document.getElementsByTagName("applicationConfigurable").length)
            .map { document.getElementsByTagName("applicationConfigurable").item(it) as Element }
        assertTrue(configurables.any { it.getAttribute("instance").contains("BridgeConfigurable") })
    }
}
