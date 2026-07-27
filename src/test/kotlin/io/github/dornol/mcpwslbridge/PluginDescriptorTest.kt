package io.github.dornol.mcpwslbridge

import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginDescriptorTest {
    @Test
    fun `plugin descriptor initializes bridge service after application startup`() {
        val stream = requireNotNull(javaClass.classLoader.getResourceAsStream("META-INF/plugin.xml"))
        val document = stream.use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }
        val services = (0 until document.getElementsByTagName("applicationService").length)
            .map { document.getElementsByTagName("applicationService").item(it) as Element }

        val bridgeService = services.single { it.getAttribute("serviceImplementation").contains("McpBridgeService") }
        assertEquals("", bridgeService.getAttribute("preload"))
        assertTrue(services.any { it.getAttribute("serviceImplementation").contains("BridgeSettings") })

        val lifecycleListeners = (0 until document.getElementsByTagName("appLifecycleListener").length)
            .map { document.getElementsByTagName("appLifecycleListener").item(it) as Element }
        assertEquals(0, document.getElementsByTagName("applicationInitializedListener").length)
        assertTrue(lifecycleListeners.any {
            it.getAttribute("implementation").contains("McpBridgeAppLifecycleListener")
        })

        val configurables = (0 until document.getElementsByTagName("applicationConfigurable").length)
            .map { document.getElementsByTagName("applicationConfigurable").item(it) as Element }
        assertTrue(configurables.any { it.getAttribute("instance").contains("BridgeConfigurable") })
    }
}
