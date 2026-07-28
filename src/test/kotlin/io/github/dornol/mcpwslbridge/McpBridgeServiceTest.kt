package io.github.dornol.mcpwslbridge

import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpBridgeServiceTest {
    @Test
    fun `disabled bridge does not open listeners`() {
        val settings = BridgeSettings()
        val service = service(settings)
        try {
            service.restart()
            assertEquals(emptyList(), service.status().runningAddresses)
            assertEquals(McpBridgeService.State.DISABLED, service.status().state)
        } finally {
            service.dispose()
        }
    }

    @Test
    fun `enabled bridge starts on construction and relays TCP requests`() {
        val targetServer = ServerSocket(0)
        val targetExecutor = Executors.newSingleThreadExecutor()
        val basePort = freeConsecutivePort()
        val settings = BridgeSettings().apply {
            update(
                BridgeSettings.State(
                    enabled = true,
                    listenerPort = basePort,
                    selectedAddresses = mutableListOf("127.0.0.1"),
                    targetMode = BridgeSettings.TargetMode.MANUAL,
                    targetHost = "127.0.0.1",
                    targetPort = targetServer.localPort,
                ),
            )
        }
        val service = service(settings)
        val targetFuture = targetExecutor.submit {
            targetServer.accept().use { target ->
                readUntilHeaders(target)
                target.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray())
                    flush()
                }
            }
        }

        try {
            await { service.status().runningAddresses == listOf("127.0.0.1") }
            assertEquals(McpBridgeService.State.CONNECTED, service.status().state)
            Socket("127.0.0.1", basePort + 1).use { client ->
                client.getOutputStream().apply {
                    write("GET /stream HTTP/1.1\r\nHost: old-address\r\n\r\n".toByteArray())
                    flush()
                }
                val response = client.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
                assertTrue(response.contains("200 OK"))
                assertTrue(response.endsWith("\r\nok"))
            }
            targetFuture.get(3, TimeUnit.SECONDS)
        } finally {
            service.dispose()
            targetServer.close()
            targetExecutor.shutdownNow()
        }
    }

    @Test
    fun `changing selected interface address rebinds the listener`() {
        val basePort = freeConsecutivePort()
        val settings = enabledSettings(basePort)
        val addresses = mutableListOf("127.0.0.1")
        val service = service(settings, addresses)
        try {
            await { service.status().runningAddresses == listOf("127.0.0.1") }
            addresses[0] = "127.0.0.2"
            service.restart()
            await { service.status().runningAddresses == listOf("127.0.0.2") }
        } finally {
            service.dispose()
        }
    }

    @Test
    fun `changing listener port moves the TCP listener`() {
        val firstPort = freeConsecutivePort()
        val secondPort = freeConsecutivePort()
        val settings = enabledSettings(firstPort)
        val service = service(settings)
        try {
            await { service.status().runningAddresses == listOf("127.0.0.1") }
            settings.update(settings.snapshot().apply { listenerPort = secondPort })
            service.restart()
            await { canConnect(secondPort + 1) }
            await { !canConnect(firstPort + 1) }
        } finally {
            service.dispose()
        }
    }

    @Test
    fun `listener bind failure is reported`() {
        val basePort = freeConsecutivePort()
        val occupied = ServerSocket(basePort + 1)
        val service = service(enabledSettings(basePort))
        try {
            await { service.status().error?.startsWith("Cannot bind") == true }
            assertTrue(service.status().runningAddresses.isEmpty())
            assertEquals(McpBridgeService.State.ERROR, service.status().state)
        } finally {
            service.dispose()
            occupied.close()
        }
    }

    @Test
    fun `missing IntelliJ MCP target remains in starting state`() {
        val settings = enabledSettings(freeConsecutivePort()).apply {
            update(snapshot().apply { targetMode = BridgeSettings.TargetMode.AUTO })
        }
        val service = McpBridgeService(
            settingsProvider = { settings },
            targetResolver = McpTargetResolver(
                optionsPathProvider = { java.nio.file.Path.of("/definitely-missing-mcp-options") },
                portProbe = { false },
            ),
            addressesProvider = { listOf("127.0.0.1") },
        )
        try {
            await {
                service.status().state == McpBridgeService.State.STARTING &&
                    service.status().error?.startsWith("IntelliJ MCP server was not found") == true
            }
            assertTrue(service.status().error.orEmpty().startsWith("IntelliJ MCP server was not found"))
        } finally {
            service.dispose()
        }
    }

    @Test
    fun `all configured distros and clients are refreshed after startup`() {
        val commands = Collections.synchronizedList(mutableListOf<List<String>>())
        val previous = WslClientConfigurator.commandRunner
        WslClientConfigurator.commandRunner = { command ->
            commands += command
            if (command.any { it.contains("getent passwd") }) {
                WslClientConfigurator.CommandResult(0, "/bin/sh")
            } else {
                WslClientConfigurator.CommandResult(0, "")
            }
        }
        val settings = enabledSettings(freeConsecutivePort()).apply {
            update(snapshot().apply {
                configuredCodexDistros = mutableListOf("Ubuntu", "Debian")
                configuredClaudeDistros = mutableListOf("Debian")
                configuredCopilotDistros = mutableListOf("Arch")
            })
        }
        val service = service(settings)
        try {
            await {
                val text = commands.joinToString(" ") { it.joinToString(" ") }.replace("'", "")
                text.contains("-d Ubuntu") && text.contains("-d Debian") && text.contains("-d Arch") &&
                    text.contains("codex mcp add") && text.contains("claude mcp add") && text.contains("copilot mcp add")
            }
        } finally {
            service.dispose()
            WslClientConfigurator.commandRunner = previous
        }
    }

    private fun service(settings: BridgeSettings, addresses: MutableList<String> = mutableListOf("127.0.0.1")): McpBridgeService = McpBridgeService(
        settingsProvider = { settings },
        targetResolver = McpTargetResolver(optionsPathProvider = { error("manual target must not read IntelliJ options") }),
        addressesProvider = { addresses.toList() },
        experimentalHttpProxyEnabled = false,
    )

    private fun enabledSettings(basePort: Int): BridgeSettings = BridgeSettings().apply {
        update(
            BridgeSettings.State(
                enabled = true,
                listenerPort = basePort,
                selectedAddresses = mutableListOf("127.0.0.1"),
                targetMode = BridgeSettings.TargetMode.MANUAL,
                targetHost = "127.0.0.1",
                targetPort = 1,
            ),
        )
    }

    private fun readUntilHeaders(socket: Socket) {
        val input = socket.getInputStream()
        var matched = 0
        while (matched < 4) {
            val next = input.read()
            if (next == -1) return
            matched = when {
                next == "\r\n\r\n"[matched].code -> matched + 1
                next == '\r'.code -> 1
                else -> 0
            }
        }
    }

    private fun await(condition: () -> Boolean) {
        repeat(60) {
            if (condition()) return
            Thread.sleep(25)
        }
        error("Condition was not met within timeout")
    }

    private fun freeConsecutivePort(): Int {
        repeat(20) {
            ServerSocket(0).use { first ->
                val candidate = first.localPort
                runCatching { ServerSocket(candidate + 1).use { return candidate } }
            }
        }
        error("Could not find two consecutive free ports")
    }

    private fun canConnect(port: Int): Boolean = runCatching {
        Socket("127.0.0.1", port).use { true }
    }.getOrDefault(false)
}
