package io.github.dornol.mcpwslbridge

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class McpTargetResolverTest {
    @Test
    fun `manual target bypasses config file and probing`() {
        val target = McpTargetResolver(optionsPathProvider = { error("options path must not be read") })
            .resolve(BridgeSettings.State(targetMode = BridgeSettings.TargetMode.MANUAL, targetHost = "10.0.0.2", targetPort = 7777))

        assertEquals(McpTarget("10.0.0.2", 7777, "Manual setting"), target)
    }

    @Test
    fun `auto target reads configured MCP port`() {
        val directory = Files.createTempDirectory("mcp-target-test")
        try {
            Files.writeString(
                directory.resolve("mcpServer.xml"),
                """
                <application>
                  <component name="McpServer">
                    <option name="port" value="65432" />
                  </component>
                </application>
                """.trimIndent(),
            )

            val target = McpTargetResolver(optionsPathProvider = { directory }).resolve(BridgeSettings.State())

            assertEquals(McpTarget("127.0.0.1", 65432, "IntelliJ MCP settings"), target)
        } finally {
            Files.deleteIfExists(directory.resolve("mcpServer.xml"))
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `missing config returns no target when no fallback port is listening`() {
        val directory = Files.createTempDirectory("mcp-target-empty")
        try {
            assertNull(McpTargetResolver({ directory }, portProbe = { false }).resolve(BridgeSettings.State()))
        } finally {
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `invalid configured port falls back to first listening candidate`() {
        val directory = Files.createTempDirectory("mcp-target-invalid")
        try {
            Files.writeString(
                directory.resolve("mcpServer.xml"),
                "<application><option name=\"port\" value=\"99999\" /></application>",
            )
            val target = McpTargetResolver(optionsPathProvider = { directory }, portProbe = { it == BridgeSettings.DEFAULT_MCP_PORT + 3 })
                .resolve(BridgeSettings.State())

            assertEquals(McpTarget("127.0.0.1", BridgeSettings.DEFAULT_MCP_PORT + 3, "Loopback port probe"), target)
        } finally {
            Files.deleteIfExists(directory.resolve("mcpServer.xml"))
            Files.deleteIfExists(directory)
        }
    }
}
