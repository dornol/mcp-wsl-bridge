package io.github.dornol.mcpwslbridge

import kotlin.test.Test
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WslClientConfiguratorTest {
    @AfterTest
    fun clearConfiguratorCaches() {
        WslClientConfigurator.clearCaches()
    }

    @Test
    fun `codex configuration removes and adds the named server using login shell`() {
        val commands = mutableListOf<List<String>>()
        val previous = WslClientConfigurator.commandRunner
        WslClientConfigurator.commandRunner = { command ->
            commands += command
            if (command.lastOrNull()?.contains("getent passwd") == true) {
                WslClientConfigurator.CommandResult(0, "/bin/bash")
            } else {
                WslClientConfigurator.CommandResult(0, "")
            }
        }
        try {
            val result = WslClientConfigurator.configureCodex("Ubuntu", "http://172.20.1.1:64343/stream")

            assertTrue(result.succeeded)
            assertEquals(3, commands.size)
            assertEquals("Ubuntu", commands[0][2])
            val commandText = commands.joinToString(" ") { it.joinToString(" ") }.replace("'", "")
            assertTrue(commandText.contains("codex mcp remove intellij-wsl-bridge"))
            assertTrue(commandText.contains("codex mcp add intellij-wsl-bridge --url http://172.20.1.1:64343/stream"))
        } finally {
            WslClientConfigurator.commandRunner = previous
        }
    }

    @Test
    fun `claude and copilot use HTTP transport`() {
        val commands = mutableListOf<List<String>>()
        val previous = WslClientConfigurator.commandRunner
        WslClientConfigurator.commandRunner = { command ->
            commands += command
            if (command.lastOrNull()?.contains("getent passwd") == true) {
                WslClientConfigurator.CommandResult(0, "/bin/sh")
            } else {
                WslClientConfigurator.CommandResult(0, "")
            }
        }
        try {
            WslClientConfigurator.configureClaudeCode("Debian", "http://172.20.1.1:64343/stream")
            WslClientConfigurator.configureCopilotCli("Arch", "http://172.20.1.1:64343/stream")

            val commandText = commands.joinToString(" ") { it.joinToString(" ") }.replace("'", "")
            assertTrue(commandText.contains("claude mcp add --scope user --transport http"))
            assertTrue(commandText.contains("copilot mcp add --transport http intellij-wsl-bridge"))
        } finally {
            WslClientConfigurator.commandRunner = previous
        }
    }

    @Test
    fun `remove commands use the expected client scopes`() {
        val commands = mutableListOf<List<String>>()
        val previous = WslClientConfigurator.commandRunner
        WslClientConfigurator.commandRunner = { command ->
            commands += command
            if (command.lastOrNull()?.contains("getent passwd") == true) {
                WslClientConfigurator.CommandResult(0, "/bin/sh")
            } else {
                WslClientConfigurator.CommandResult(0, "")
            }
        }
        try {
            WslClientConfigurator.removeCodex("Ubuntu", "server-one")
            WslClientConfigurator.removeClaudeCode("Ubuntu", "server-two")
            WslClientConfigurator.removeCopilotCli("Ubuntu", "server-three")

            val commandText = commands.joinToString(" ") { it.joinToString(" ") }.replace("'", "")
            assertTrue(commandText.contains("codex mcp remove server-one"))
            assertTrue(commandText.contains("claude mcp remove --scope user server-two"))
            assertTrue(commandText.contains("copilot mcp remove server-three"))
        } finally {
            WslClientConfigurator.commandRunner = previous
        }
    }

    @Test
    fun `generic JSON contains streamable HTTP endpoint`() {
        val json = WslClientConfigurator.genericJson("http://127.0.0.1:64343/stream")

        assertTrue(json.contains("\"intellij-wsl-bridge\""))
        assertTrue(json.contains("http://127.0.0.1:64343/stream"))
    }

    @Test
    fun `generic JSON contains multiple named endpoints`() {
        val json = WslClientConfigurator.genericJson(
            linkedMapOf(
                "intellij" to "http://127.0.0.1:64343/mcp/intellij",
                "intellij-index" to "http://127.0.0.1:64343/mcp/index",
            ),
        )

        assertTrue(json.indexOf("\"intellij\"") < json.indexOf("\"intellij-index\""))
        assertTrue(json.contains("/mcp/intellij"))
        assertTrue(json.contains("/mcp/index"))
    }

    @Test
    fun `failed add command is reported to caller`() {
        val previous = WslClientConfigurator.commandRunner
        WslClientConfigurator.commandRunner = { command ->
            if (command.any { it.contains("getent passwd") }) {
                WslClientConfigurator.CommandResult(0, "/bin/sh")
            } else if (command.lastOrNull()?.contains("mcp") == true && command.lastOrNull()?.contains("add") == true) {
                WslClientConfigurator.CommandResult(1, "copilot is not installed")
            } else {
                WslClientConfigurator.CommandResult(0, "")
            }
        }
        try {
            val result = WslClientConfigurator.configureCopilotCli("Ubuntu", "http://127.0.0.1:64343/stream")

            assertTrue(!result.succeeded)
            assertTrue(result.output.contains("not installed"))
        } finally {
            WslClientConfigurator.commandRunner = previous
        }
    }

    @Test
    fun `unavailable client command is reported without configuring it`() {
        val commands = mutableListOf<List<String>>()
        val previous = WslClientConfigurator.commandRunner
        WslClientConfigurator.commandRunner = { command ->
            commands += command
            when {
                command.any { it.contains("getent passwd") } -> WslClientConfigurator.CommandResult(0, "/bin/sh")
                command.lastOrNull()?.contains("command") == true -> WslClientConfigurator.CommandResult(1, "not found")
                else -> WslClientConfigurator.CommandResult(0, "")
            }
        }
        try {
            assertTrue(!WslClientConfigurator.isCommandAvailable("Ubuntu", "claude"))
            assertTrue(commands.any { it.lastOrNull()?.contains("command") == true })
            assertTrue(commands.none { it.joinToString(" ").contains("claude mcp") })
        } finally {
            WslClientConfigurator.commandRunner = previous
        }
    }

    @Test
    fun `distribution parsing removes WSL null padding and blank lines`() {
        assertEquals(listOf("Ubuntu", "Debian"), WslClientConfigurator.parseDistributions("\u0000Ubuntu\u0000\n\nDebian\u0000"))
    }

    @Test
    fun `shell quoting protects spaces and apostrophes`() {
        assertEquals("'Ubuntu'", WslClientConfigurator.shellQuote("Ubuntu"))
        assertEquals("'O'\\\"'\\\"'Reilly'", WslClientConfigurator.shellQuote("O'Reilly"))
        assertEquals("'hello world'", WslClientConfigurator.shellQuote("hello world"))
    }

    @Test
    fun `loopback proxy installation returns fixed local endpoint`() {
        val commands = mutableListOf<List<String>>()
        val previous = WslClientConfigurator.commandRunner
        WslClientConfigurator.commandRunner = { command ->
            commands += command
            if (command.any { it.contains("getent passwd") }) {
                WslClientConfigurator.CommandResult(0, "/bin/sh")
            } else {
                WslClientConfigurator.CommandResult(0, "")
            }
        }
        try {
            assertEquals(
                "http://127.0.0.1:64344/stream",
                WslClientConfigurator.ensureLoopbackProxy("Ubuntu", "http://172.20.1.1:64343/stream"),
            )
            assertTrue(commands.any { it.joinToString(" ").contains("base64 -d") })
            assertTrue(commands.any { it.joinToString(" ").contains("nohup node") })
        } finally {
            WslClientConfigurator.commandRunner = previous
        }
    }
}
