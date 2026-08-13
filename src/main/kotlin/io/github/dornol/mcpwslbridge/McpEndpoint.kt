package io.github.dornol.mcpwslbridge

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object McpEndpoint {
    fun url(address: String, port: Int, path: String, state: BridgeSettings.State): String {
        val base = "http://$address:$port${if (path.startsWith('/')) path else "/$path"}"
        return if (state.authEnabled && state.authToken.isNotBlank()) {
            "$base?token=${URLEncoder.encode(state.authToken, StandardCharsets.UTF_8)}"
        } else {
            base
        }
    }
}
