package io.github.dornol.mcpwslbridge

/** A public HTTP path mapped to one MCP server endpoint. */
data class McpRoute(
    val publicPath: String,
    val target: McpTarget,
    val targetPath: String,
)

fun normalizeMcpPath(value: String): String {
    val trimmed = value.trim()
    require(trimmed.startsWith("/")) { "MCP path must start with '/'." }
    require(!trimmed.contains("//") && !trimmed.contains("..")) { "MCP path contains an invalid segment." }
    return if (trimmed.length > 1) trimmed.trimEnd('/') else trimmed
}
