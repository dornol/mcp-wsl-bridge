package io.github.dornol.mcpwslbridge

import com.intellij.openapi.diagnostic.Logger
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Experimental HTTP/1.1 reverse proxy used to validate direct Claude Code access from WSL. */
class ExperimentalHttpReverseProxy(
    private val executor: Executor,
    sessionDirectory: Path,
) {
    constructor(executor: Executor) : this(
        executor,
        com.intellij.openapi.application.PathManager.getConfigDir().resolve("mcp-wsl-bridge/sessions"),
    )
    private val log = Logger.getInstance(ExperimentalHttpReverseProxy::class.java)
    private val exchangeSequence = AtomicLong()
    private val client = HttpClient.newBuilder().executor(executor).version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(3)).build()
    private val servers = mutableMapOf<String, HttpServer>()
    private val activeExchanges = ConcurrentHashMap.newKeySet<HttpExchange>()
    private val virtualSessions = VirtualMcpSessionStore(sessionDirectory)

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
        activeExchanges.forEach { exchange -> runCatching { exchange.close() } }
        activeExchanges.clear()
        servers.values.forEach { it.stop(0) }
        servers.clear()
    }

    private fun forward(exchange: HttpExchange, routes: List<McpRoute>) {
        val exchangeId = exchangeSequence.incrementAndGet()
        val startedAt = System.nanoTime()
        activeExchanges.add(exchange)
        try {
            val route = routes.firstOrNull { matches(it.publicPath, exchange.requestURI.rawPath) }
                ?: return sendNotFound(exchange)
            val sessionId = exchange.requestHeaders.getFirst("Mcp-Session-Id")
            log.debug(
                "MCP[$exchangeId] inbound ${exchange.requestMethod} ${exchange.requestURI.rawPath} " +
                    "remote=${exchange.remoteAddress.address.hostAddress} " +
                    "${mcpHeaderSummary(exchange)} target=${route.target.host}:${route.target.port}",
            )
            val requestBytes = if (exchange.requestMethod in BODY_METHODS) exchange.requestBody.readBytes() else ByteArray(0)
            if (requestBytes.isNotEmpty()) logRequestSummary(exchangeId, requestBytes)
            val initialize = requestBytes.isNotEmpty() && JSON_INITIALIZE.containsMatchIn(String(requestBytes, Charsets.UTF_8))
            val virtualSession = sessionId?.let { virtualSessions.find(it) }
            if (sessionId != null && virtualSession == null) {
                log.debug("MCP[$exchangeId] rejecting unknown virtual session=$sessionId")
                return sendSessionExpired(exchange)
            }

            var upstreamSessionId = virtualSession?.upstreamId
            if (virtualSession != null && upstreamSessionId == null) {
                upstreamSessionId = initializeUpstream(route, exchange, virtualSession.initializeBody, exchangeId)
                if (upstreamSessionId == null) return sendBadGateway(exchange)
                virtualSessions.rebind(virtualSession, upstreamSessionId)
            }

            var response = try {
                sendUpstream(route, exchange, requestBytes, upstreamSessionId, exchangeId, HttpResponse.BodyHandlers.ofInputStream())
            } catch (error: Exception) {
                if (virtualSession == null) throw error
                log.debug("MCP[$exchangeId] upstream connection failed; reinitializing virtual session=${virtualSession.virtualId}", error)
                reinitializeAndRetry(route, exchange, requestBytes, virtualSession, exchangeId)
            }
            if (response.statusCode() == 404 && virtualSession != null) {
                response.body().close()
                response = reinitializeAndRetry(route, exchange, requestBytes, virtualSession, exchangeId)
            }
            if (initialize && response.statusCode() == 200) {
                val responseBytes = response.body().readAllBytes()
                response.body().close()
                val upstreamId = response.headers().firstValue("mcp-session-id").orElse(null)
                if (upstreamId != null) {
                    val created = virtualSessions.create(requestBytes, upstreamId)
                    return sendBufferedResponse(exchange, response.statusCode(), response.headers(), responseBytes, created.virtualId)
                }
                return sendBufferedResponse(exchange, response.statusCode(), response.headers(), responseBytes, null)
            }
            log.debug(
                "MCP[$exchangeId] upstream response status=${response.statusCode()} " +
                    "contentType=${response.headers().firstValue("content-type").orElse("")} " +
                    "session=${response.headers().firstValue("mcp-session-id").orElse("")}",
            )
            response.headers().map().forEach { (name, values) ->
                if (name.lowercase() !in RESPONSE_HOP_HEADERS && name.lowercase() != "content-length" && name.lowercase() != "mcp-session-id") {
                    exchange.responseHeaders.put(name, values)
                }
            }
            virtualSession?.let { exchange.responseHeaders.set("Mcp-Session-Id", it.virtualId) }
            // Do not reuse the upstream Content-Length. MCP responses may be SSE streams
            // and the JDK HttpServer must frame the downstream response itself. Keeping the
            // upstream length can truncate or stall a response when the server emits an
            // approval request before the final JSON-RPC response.
            exchange.sendResponseHeaders(response.statusCode(), 0)
            val contentType = response.headers().firstValue("content-type").orElse("")
            response.body().use { input ->
                exchange.responseBody.use { output ->
                    copyResponse(exchangeId, response.statusCode(), contentType, input, output)
                }
            }
            log.debug("MCP[$exchangeId] completed in ${elapsedMillis(startedAt)}ms")
        } catch (error: Exception) {
            log.warn("MCP[$exchangeId] failed after ${elapsedMillis(startedAt)}ms: ${error.message}", error)
            runCatching { exchange.sendResponseHeaders(502, -1) }
            exchange.close()
        } finally {
            activeExchanges.remove(exchange)
        }
    }

    private fun <T> sendUpstream(
        route: McpRoute,
        exchange: HttpExchange,
        requestBytes: ByteArray,
        upstreamSessionId: String?,
        exchangeId: Long,
        bodyHandler: HttpResponse.BodyHandler<T>,
        requestMethod: String = exchange.requestMethod,
        initializeRequest: Boolean = false,
    ): HttpResponse<T> {
        val targetPath = rewritePath(route, exchange.requestURI.rawPath)
        val request = HttpRequest.newBuilder(URI("http", null, route.target.host, route.target.port, targetPath, exchange.requestURI.rawQuery, null))
            .version(HttpClient.Version.HTTP_1_1)
        exchange.requestHeaders.forEach { (name, values) ->
            if (name.lowercase() !in REQUEST_HOP_HEADERS && name.lowercase() != "mcp-session-id") values.forEach { request.header(name, it) }
        }
        if (upstreamSessionId != null) request.header("Mcp-Session-Id", upstreamSessionId)
        request.header("Origin", "http://${route.target.host}:${route.target.port}")
        if (initializeRequest) {
            request.setHeader("Accept", "application/json, text/event-stream")
            request.setHeader("Content-Type", "application/json")
        }
        val body = if (requestBytes.isNotEmpty()) HttpRequest.BodyPublishers.ofByteArray(requestBytes) else HttpRequest.BodyPublishers.noBody()
        return client.send(request.method(requestMethod, body).build(), bodyHandler)
    }

    private fun initializeUpstream(route: McpRoute, exchange: HttpExchange, body: ByteArray, exchangeId: Long): String? {
        val response = sendUpstream(route, exchange, body, null, exchangeId, HttpResponse.BodyHandlers.ofByteArray(), "POST", true)
        val upstreamId = response.headers().firstValue("mcp-session-id").orElse(null)
        log.debug("MCP[$exchangeId] virtual session initialize status=${response.statusCode()} upstreamSession=${upstreamId ?: ""}")
        return if (response.statusCode() in 200..299) upstreamId else null
    }

    private fun reinitializeAndRetry(
        route: McpRoute,
        exchange: HttpExchange,
        requestBytes: ByteArray,
        virtualSession: VirtualMcpSession,
        exchangeId: Long,
    ): HttpResponse<InputStream> {
        virtualSessions.invalidate(virtualSession)
        val reboundSessionId = initializeUpstream(route, exchange, virtualSession.initializeBody, exchangeId)
            ?: throw java.io.IOException("IntelliJ MCP session could not be reinitialized")
        virtualSessions.rebind(virtualSession, reboundSessionId)
        return sendUpstream(route, exchange, requestBytes, reboundSessionId, exchangeId, HttpResponse.BodyHandlers.ofInputStream())
    }

    private fun sendBufferedResponse(
        exchange: HttpExchange,
        statusCode: Int,
        headers: HttpHeaders,
        body: ByteArray,
        virtualSessionId: String?,
    ) {
        headers.map().forEach { (name, values) ->
            if (name.lowercase() !in RESPONSE_HOP_HEADERS && name.lowercase() != "content-length" && name.lowercase() != "mcp-session-id") {
                exchange.responseHeaders.put(name, values)
            }
        }
        if (virtualSessionId != null) exchange.responseHeaders.set("Mcp-Session-Id", virtualSessionId)
        exchange.sendResponseHeaders(statusCode, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private fun sendBadGateway(exchange: HttpExchange) {
        runCatching { exchange.sendResponseHeaders(502, -1) }
        exchange.close()
    }

    private fun copyResponse(exchangeId: Long, statusCode: Int, contentType: String, input: InputStream, output: OutputStream) {
        val buffer = ByteArray(8192)
        val sseLine = StringBuilder()
        val jsonBody = StringBuilder()
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            output.write(buffer, 0, count)
            output.flush()
            if (contentType.contains("application/json", ignoreCase = true) && jsonBody.length < MAX_LOG_BUFFER) {
                jsonBody.append(String(buffer, 0, minOf(count, MAX_LOG_BUFFER - jsonBody.length), Charsets.UTF_8))
            }
            if (sseLine.length < MAX_LOG_BUFFER) {
                sseLine.append(String(buffer, 0, count, Charsets.UTF_8))
                logSseMessages(exchangeId, sseLine)
            }
        }
        if (jsonBody.isNotEmpty()) logJsonSummary(exchangeId, statusCode, jsonBody.toString())
    }

    private fun logJsonSummary(exchangeId: Long, statusCode: Int, body: String) {
        val errors = listOf(JSON_ERROR_MESSAGE, JSON_RPC_ERROR_MESSAGE)
            .mapNotNull { it.find(body)?.groupValues?.get(1) }
            .distinct()
        if (errors.isNotEmpty()) {
            log.debug("MCP[$exchangeId] JSON response status=$statusCode errors=${errors.joinToString(" | ")}")
        } else {
            log.debug("MCP[$exchangeId] JSON response status=$statusCode without error fields")
        }
    }

    private fun logRequestSummary(exchangeId: Long, body: ByteArray) {
        val text = String(body, 0, minOf(body.size, MAX_LOG_BUFFER), Charsets.UTF_8)
        val method = JSON_METHOD.find(text)?.groupValues?.get(1) ?: return
        val id = JSON_ID.find(text)?.groupValues?.get(1).orEmpty()
        val tool = JSON_TOOL_NAME.find(text)?.groupValues?.get(1)
        log.debug("MCP[$exchangeId] request method=$method id=$id${tool?.let { " tool=$it" }.orEmpty()}")
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

    private fun sendSessionExpired(exchange: HttpExchange) {
        exchange.responseHeaders.add("Connection", "close")
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.sendResponseHeaders(404, -1)
        exchange.close()
    }

    private companion object {
        val BODY_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")
        val REQUEST_HOP_HEADERS = setOf("host", "origin", "connection", "keep-alive", "content-length", "transfer-encoding", "upgrade")
        val RESPONSE_HOP_HEADERS = setOf("connection", "keep-alive", "content-length", "transfer-encoding", "upgrade")
        const val MAX_LOG_BUFFER = 256 * 1024
        val JSON_METHOD = Regex("\\\"method\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
        val JSON_INITIALIZE = Regex("\\\"method\\\"\\s*:\\s*\\\"initialize\\\"")
        val JSON_ID = Regex("\\\"id\\\"\\s*:\\s*(\\\"[^\\\"]*\\\"|-?\\d+)")
        val JSON_TOOL_NAME = Regex("\\\"name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
        val JSON_ERROR_MESSAGE = Regex("\\\"errorMessage\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"")
        val JSON_RPC_ERROR_MESSAGE = Regex("\\\"error\\\"\\s*:\\s*\\{[^}]*\\\"message\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"")
    }
}
