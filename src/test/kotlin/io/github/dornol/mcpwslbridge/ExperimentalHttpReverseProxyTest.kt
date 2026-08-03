package io.github.dornol.mcpwslbridge

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.Executors
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExperimentalHttpReverseProxyTest {
    @Test
    fun `proxy forwards method path body and response`() {
        val executor = Executors.newCachedThreadPool()
        val target = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 16)
        var receivedBody = ""
        var receivedPath = ""
        target.createContext("/") { exchange ->
            receivedBody = exchange.requestBody.bufferedReader().readText()
            receivedPath = exchange.requestURI.toString()
            val response = "ok"
            exchange.sendResponseHeaders(201, response.length.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }
        target.executor = executor
        target.start()

        val proxy = ExperimentalHttpReverseProxy(executor)
        val proxyPort = freePort()
        try {
            proxy.start("127.0.0.1", proxyPort, McpTarget("127.0.0.1", target.address.port, "test"))
            val response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                    .uri(java.net.URI("http://127.0.0.1:$proxyPort/mcp?session=1"))
                    .header("Origin", "http://old-host")
                    .POST(HttpRequest.BodyPublishers.ofString("payload"))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )

            assertEquals(201, response.statusCode())
            assertEquals("ok", response.body())
            assertEquals("payload", receivedBody)
            assertEquals("/mcp?session=1", receivedPath)
        } finally {
            proxy.stop()
            target.stop(0)
            executor.shutdownNow()
        }
    }

    @Test
    fun `proxy returns 502 when target is unavailable`() {
        val executor = Executors.newCachedThreadPool()
        val proxy = ExperimentalHttpReverseProxy(executor)
        val proxyPort = freePort()
        val unusedTargetPort = freePort()
        try {
            proxy.start("127.0.0.1", proxyPort, McpTarget("127.0.0.1", unusedTargetPort, "test"))
            val response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(java.net.URI("http://127.0.0.1:$proxyPort/stream")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )

            assertEquals(502, response.statusCode())
        } finally {
            proxy.stop()
            executor.shutdownNow()
        }
    }

    @Test
    fun `proxy expires sessions after restart`() {
        val executor = Executors.newCachedThreadPool()
        val target = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 16)
        target.createContext("/") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.close()
        }
        target.executor = executor
        target.start()

        val proxy = ExperimentalHttpReverseProxy(executor)
        val proxyPort = freePort()
        val client = HttpClient.newHttpClient()
        try {
            val targetConfig = McpTarget("127.0.0.1", target.address.port, "test")
            proxy.start("127.0.0.1", proxyPort, targetConfig)
            assertEquals(
                200,
                client.send(
                    HttpRequest.newBuilder()
                        .uri(java.net.URI("http://127.0.0.1:$proxyPort/stream"))
                        .header("Mcp-Session-Id", "old-session")
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                ).statusCode(),
            )

            proxy.stop()
            proxy.start("127.0.0.1", proxyPort, targetConfig)

            assertEquals(
                404,
                client.send(
                    HttpRequest.newBuilder()
                        .uri(java.net.URI("http://127.0.0.1:$proxyPort/stream"))
                        .header("Mcp-Session-Id", "old-session")
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                ).statusCode(),
            )
        } finally {
            proxy.stop()
            target.stop(0)
            executor.shutdownNow()
        }
    }

    @Test
    fun `proxy preserves streamable HTTP approval exchange and session headers`() {
        val executor = Executors.newCachedThreadPool()
        val target = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 16)
        val receivedSessions = CopyOnWriteArrayList<String>()
        val receivedOrigins = CopyOnWriteArrayList<String>()
        val receivedBodies = CopyOnWriteArrayList<String>()
        target.createContext("/stream") { exchange ->
            receivedSessions += exchange.requestHeaders.getFirst("Mcp-Session-Id").orEmpty()
            receivedOrigins += exchange.requestHeaders.getFirst("Origin").orEmpty()
            val body = exchange.requestBody.bufferedReader().readText()
            receivedBodies += body
            if (body.contains("tools/call")) {
                val response = """
                    event: message
                    data: {"jsonrpc":"2.0","id":99,"method":"elicitation/create","params":{"message":"Allow query?"}}

                    event: message
                    data: {"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"ok"}]}}

                """.trimIndent() + "\n"
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.responseHeaders.add("Mcp-Session-Id", "session-1")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { it.write(response.toByteArray()) }
            } else {
                exchange.sendResponseHeaders(202, -1)
                exchange.close()
            }
        }
        target.executor = executor
        target.start()

        val proxy = ExperimentalHttpReverseProxy(executor)
        val proxyPort = freePort()
        try {
            proxy.start("127.0.0.1", proxyPort, McpTarget("127.0.0.1", target.address.port, "test"))
            val response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                    .uri(java.net.URI("http://127.0.0.1:$proxyPort/stream"))
                    .header("Origin", "http://wsl-gateway:64343")
                    .header("Mcp-Session-Id", "session-1")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"method\":\"tools/call\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )

            assertEquals(200, response.statusCode())
            assertTrue(response.body().contains("elicitation/create"), response.body())
            assertTrue(response.body().contains("\"result\""), response.body())
            assertEquals(listOf("session-1"), receivedSessions)
            assertEquals(listOf("http://127.0.0.1:${target.address.port}"), receivedOrigins)
            assertEquals("session-1", response.headers().firstValue("Mcp-Session-Id").orElse(null))

            val approvalResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                    .uri(java.net.URI("http://127.0.0.1:$proxyPort/stream"))
                    .header("Origin", "http://wsl-gateway:64343")
                    .header("Mcp-Session-Id", "session-1")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":99,\"result\":{\"action\":\"accept\"}}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(202, approvalResponse.statusCode())
            assertEquals(2, receivedBodies.size)
            assertTrue(receivedBodies[1].contains("accept"), receivedBodies[1])
            assertEquals(listOf("session-1", "session-1"), receivedSessions)
            assertEquals(listOf("http://127.0.0.1:${target.address.port}", "http://127.0.0.1:${target.address.port}"), receivedOrigins)
        } finally {
            proxy.stop()
            target.stop(0)
            executor.shutdownNow()
        }
    }

    @Test
    fun `proxy routes multiple public paths through one listener`() {
        val executor = Executors.newCachedThreadPool()
        val first = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 16)
        val second = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 16)
        first.createContext("/first") { exchange ->
            exchange.sendResponseHeaders(200, 5)
            exchange.responseBody.use { it.write("first".toByteArray()) }
        }
        second.createContext("/second") { exchange ->
            exchange.sendResponseHeaders(200, 6)
            exchange.responseBody.use { it.write("second".toByteArray()) }
        }
        first.executor = executor
        second.executor = executor
        first.start()
        second.start()
        val proxy = ExperimentalHttpReverseProxy(executor)
        val proxyPort = freePort()
        try {
            proxy.start(
                "127.0.0.1",
                proxyPort,
                listOf(
                    McpRoute("/mcp/one", McpTarget("127.0.0.1", first.address.port, "first"), "/first"),
                    McpRoute("/mcp/two", McpTarget("127.0.0.1", second.address.port, "second"), "/second"),
                ),
            )
            val client = HttpClient.newHttpClient()
            assertEquals(
                "first",
                client.send(
                    HttpRequest.newBuilder().uri(java.net.URI("http://127.0.0.1:$proxyPort/mcp/one")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                ).body(),
            )
            assertEquals(
                "second",
                client.send(
                    HttpRequest.newBuilder().uri(java.net.URI("http://127.0.0.1:$proxyPort/mcp/two")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                ).body(),
            )
            assertEquals(
                404,
                client.send(
                    HttpRequest.newBuilder().uri(java.net.URI("http://127.0.0.1:$proxyPort/mcp/unknown")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                ).statusCode(),
            )
        } finally {
            proxy.stop()
            first.stop(0)
            second.stop(0)
            executor.shutdownNow()
        }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}
