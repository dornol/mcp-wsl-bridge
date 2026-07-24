package io.github.dornol.mcpwslbridge

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executor

/** Experimental HTTP/1.1 reverse proxy used to validate direct Claude Code access from WSL. */
class ExperimentalHttpReverseProxy(private val executor: Executor) {
    private val client = HttpClient.newBuilder().executor(executor).version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(3)).build()
    private val servers = mutableMapOf<String, HttpServer>()

    fun start(address: String, port: Int, target: McpTarget) {
        if (servers.containsKey(address)) return
        val server = HttpServer.create(InetSocketAddress(address, port), 32)
        server.createContext("/") { exchange -> forward(exchange, target) }
        server.executor = executor
        server.start()
        servers[address] = server
    }

    fun stop() {
        servers.values.forEach { it.stop(0) }
        servers.clear()
    }

    private fun forward(exchange: HttpExchange, target: McpTarget) {
        try {
            val request = HttpRequest.newBuilder(URI("http", null, target.host, target.port, exchange.requestURI.rawPath, exchange.requestURI.rawQuery, null))
                .version(HttpClient.Version.HTTP_1_1)
            exchange.requestHeaders.forEach { (name, values) ->
                if (name.lowercase() !in REQUEST_HOP_HEADERS) values.forEach { request.header(name, it) }
            }
            val body = if (exchange.requestMethod in BODY_METHODS) HttpRequest.BodyPublishers.ofInputStream { exchange.requestBody } else HttpRequest.BodyPublishers.noBody()
            val response = client.send(request.method(exchange.requestMethod, body).build(), HttpResponse.BodyHandlers.ofInputStream())
            response.headers().map().forEach { (name, values) ->
                if (name.lowercase() !in RESPONSE_HOP_HEADERS) exchange.responseHeaders.put(name, values)
            }
            exchange.sendResponseHeaders(response.statusCode(), 0)
            response.body().use { input -> exchange.responseBody.use { input.copyTo(it) } }
        } catch (_: Exception) {
            runCatching { exchange.sendResponseHeaders(502, -1) }
            exchange.close()
        }
    }

    private companion object {
        val BODY_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")
        val REQUEST_HOP_HEADERS = setOf("host", "origin", "connection", "keep-alive", "content-length", "transfer-encoding", "upgrade")
        val RESPONSE_HOP_HEADERS = setOf("connection", "keep-alive", "content-length", "transfer-encoding", "upgrade")
    }
}
