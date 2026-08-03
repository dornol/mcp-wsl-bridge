package io.github.dornol.mcpwslbridge

import com.intellij.openapi.diagnostic.Logger
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

/** Experimental HTTP/1.1 reverse proxy used to validate direct Claude Code access from WSL. */
class ExperimentalHttpReverseProxy(private val executor: Executor) {
    private val log = Logger.getInstance(ExperimentalHttpReverseProxy::class.java)
    private val exchangeSequence = AtomicLong()
    private val client = HttpClient.newBuilder().executor(executor).version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(3)).build()
    private val servers = mutableMapOf<String, HttpServer>()

    fun start(address: String, port: Int, target: McpTarget) {
        start(address, port, listOf(McpRoute("/", target, "/")))
    }

    fun start(address: String, port: Int, routes: List<McpRoute>) {
        if (servers.containsKey(address)) return
        require(routes.isNotEmpty()) { "At least one MCP route is required." }
        val normalizedRoutes = routes.map { route ->
            route.copy(
                publicPath = normalizeMcpPath(route.publicPath),
                targetPath = normalizeMcpPath(route.targetPath),
            )
        }.sortedByDescending { it.publicPath.length }
        require(normalizedRoutes.map { it.publicPath }.distinct().size == normalizedRoutes.size) {
            "MCP public paths must be unique."
        }
        val server = HttpServer.create(InetSocketAddress(address, port), 32)
        server.createContext("/") { exchange -> forward(exchange, normalizedRoutes) }
        server.executor = executor
        server.start()
        servers[address] = server
    }

    fun stop() {
        servers.values.forEach { it.stop(0) }
        servers.clear()
    }

    private fun forward(exchange: HttpExchange, routes: List<McpRoute>) {
        val exchangeId = exchangeSequence.incrementAndGet()
        val startedAt = System.nanoTime()
        try {
            val route = routes.firstOrNull { matches(it.publicPath, exchange.requestURI.rawPath) }
                ?: return sendNotFound(exchange)
            val targetPath = rewritePath(route, exchange.requestURI.rawPath)
            val targetOrigin = "http://${route.target.host}:${route.target.port}"
            log.debug(
                "MCP[$exchangeId] inbound ${exchange.requestMethod} ${exchange.requestURI.rawPath} " +
                    "remote=${exchange.remoteAddress.address.hostAddress} " +
                    "${mcpHeaderSummary(exchange)} target=${route.target.host}:${route.target.port}$targetPath",
            )
            val request = HttpRequest.newBuilder(URI("http", null, route.target.host, route.target.port, targetPath, exchange.requestURI.rawQuery, null))
                .version(HttpClient.Version.HTTP_1_1)
            exchange.requestHeaders.forEach { (name, values) ->
                if (name.lowercase() !in REQUEST_HOP_HEADERS) values.forEach { request.header(name, it) }
            }
            // IntelliJ's MCP server validates local HTTP origins. The WSL client origin
            // identifies the bridge/gateway, so replace it with the loopback origin that
            // IntelliJ sees when a client connects directly on Windows. This is also
            // important for server-side approval flows, which are policy-checked before
            // the tool is executed.
            request.header("Origin", targetOrigin)
            val body = if (exchange.requestMethod in BODY_METHODS) HttpRequest.BodyPublishers.ofInputStream { exchange.requestBody } else HttpRequest.BodyPublishers.noBody()
            val response = client.send(request.method(exchange.requestMethod, body).build(), HttpResponse.BodyHandlers.ofInputStream())
            log.debug(
                "MCP[$exchangeId] upstream response status=${response.statusCode()} " +
                    "contentType=${response.headers().firstValue("content-type").orElse("")} " +
                    "session=${response.headers().firstValue("mcp-session-id").orElse("")}",
            )
            response.headers().map().forEach { (name, values) ->
                if (name.lowercase() !in RESPONSE_HOP_HEADERS && name.lowercase() != "content-length") {
                    exchange.responseHeaders.put(name, values)
                }
            }
            // Do not reuse the upstream Content-Length. MCP responses may be SSE streams
            // and the JDK HttpServer must frame the downstream response itself. Keeping the
            // upstream length can truncate or stall a response when the server emits an
            // approval request before the final JSON-RPC response.
            exchange.sendResponseHeaders(response.statusCode(), 0)
            response.body().use { input -> exchange.responseBody.use { output -> copyResponse(exchangeId, input, output) } }
            log.debug("MCP[$exchangeId] completed in ${elapsedMillis(startedAt)}ms")
        } catch (error: Exception) {
            log.warn("MCP[$exchangeId] failed after ${elapsedMillis(startedAt)}ms: ${error.message}", error)
            runCatching { exchange.sendResponseHeaders(502, -1) }
            exchange.close()
        }
    }

    private fun copyResponse(exchangeId: Long, input: InputStream, output: OutputStream) {
        val buffer = ByteArray(8192)
        val sseLine = StringBuilder()
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            output.write(buffer, 0, count)
            output.flush()
            if (sseLine.length < MAX_LOG_BUFFER) {
                sseLine.append(String(buffer, 0, count, Charsets.UTF_8))
                logSseMessages(exchangeId, sseLine)
            }
        }
    }

    private fun logSseMessages(exchangeId: Long, buffer: StringBuilder) {
        while (true) {
            val separator = buffer.indexOf("\n\n")
            if (separator < 0) return
            val event = buffer.substring(0, separator)
            buffer.delete(0, separator + 2)
            val data = event.lineSequence()
                .filter { it.startsWith("data:") }
                .joinToString("\n") { it.removePrefix("data:").trimStart() }
            if (data.isBlank()) continue
            val method = JSON_METHOD.find(data)?.groupValues?.get(1)
            val id = JSON_ID.find(data)?.groupValues?.get(1)
            log.debug("MCP[$exchangeId] SSE message method=${method ?: "response/notification"} id=${id ?: "-"}")
        }
    }

    private fun mcpHeaderSummary(exchange: HttpExchange): String = listOf(
        "origin=${exchange.requestHeaders.getFirst("Origin") ?: ""}",
        "host=${exchange.requestHeaders.getFirst("Host") ?: ""}",
        "session=${exchange.requestHeaders.getFirst("Mcp-Session-Id") ?: ""}",
        "protocol=${exchange.requestHeaders.getFirst("Mcp-Protocol-Version") ?: ""}",
        "accept=${exchange.requestHeaders.getFirst("Accept") ?: ""}",
        "contentType=${exchange.requestHeaders.getFirst("Content-Type") ?: ""}",
        "lastEventId=${exchange.requestHeaders.getFirst("Last-Event-ID") ?: ""}",
    ).joinToString(" ")

    private fun elapsedMillis(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000

    private fun matches(publicPath: String, requestPath: String): Boolean =
        publicPath == "/" || requestPath == publicPath || requestPath.startsWith("$publicPath/")

    private fun rewritePath(route: McpRoute, requestPath: String): String {
        if (route.publicPath == "/") return requestPath
        val suffix = requestPath.removePrefix(route.publicPath)
        return if (suffix.isEmpty()) route.targetPath else route.targetPath.trimEnd('/') + suffix
    }

    private fun sendNotFound(exchange: HttpExchange) {
        exchange.sendResponseHeaders(404, -1)
        exchange.close()
    }

    private companion object {
        val BODY_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")
        val REQUEST_HOP_HEADERS = setOf("host", "origin", "connection", "keep-alive", "content-length", "transfer-encoding", "upgrade")
        val RESPONSE_HOP_HEADERS = setOf("connection", "keep-alive", "content-length", "transfer-encoding", "upgrade")
        const val MAX_LOG_BUFFER = 256 * 1024
        val JSON_METHOD = Regex("\\\"method\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
        val JSON_ID = Regex("\\\"id\\\"\\s*:\\s*(\\\"[^\\\"]*\\\"|-?\\d+)")
    }
}
