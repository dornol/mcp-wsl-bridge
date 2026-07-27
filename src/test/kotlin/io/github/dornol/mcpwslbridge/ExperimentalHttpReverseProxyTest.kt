package io.github.dornol.mcpwslbridge

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.Executors
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

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}
