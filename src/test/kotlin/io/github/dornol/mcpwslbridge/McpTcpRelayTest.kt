package io.github.dornol.mcpwslbridge

import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpTcpRelayTest {
    @Test
    fun `relay rewrites loopback headers and preserves body and response`() {
        val executor = Executors.newCachedThreadPool()
        val targetServer = ServerSocket(0)
        val bridgeServer = ServerSocket(0)
        val target = McpTarget("127.0.0.1", targetServer.localPort, "test")
        val relay = McpTcpRelay(executor) { target }

        val targetFuture = executor.submit<String> {
            targetServer.accept().use { socket ->
                val request = readRequest(socket)
                val response = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok"
                socket.getOutputStream().apply {
                    write(response.toByteArray())
                    flush()
                }
                request
            }
        }
        val relayFuture = executor.submit {
            bridgeServer.accept().use(relay::relay)
        }

        try {
            Socket("127.0.0.1", bridgeServer.localPort).use { client ->
                client.getOutputStream().apply {
                    write(
                        ("POST /stream?session=1 HTTP/1.1\r\n" +
                            "Host: old-wsl-address:64343\r\n" +
                            "Origin: http://old-wsl-address:64343\r\n" +
                            "Content-Length: 7\r\n\r\n" +
                            "payload").toByteArray(),
                    )
                    flush()
                }
                val response = client.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
                assertTrue(response.endsWith("\r\nok"), response)
            }

            val request = targetFuture.get(3, TimeUnit.SECONDS)
            assertTrue(request.startsWith("POST /stream?session=1 HTTP/1.1"))
            assertTrue(request.contains("Host: 127.0.0.1:${targetServer.localPort}"))
            assertTrue(request.contains("Origin: http://127.0.0.1:${targetServer.localPort}"))
            assertTrue(request.endsWith("\r\n\r\npayload"))
            relayFuture.get(3, TimeUnit.SECONDS)
        } finally {
            targetServer.close()
            bridgeServer.close()
            executor.shutdownNow()
        }
    }

    private fun readRequest(socket: Socket): String {
        val input = socket.getInputStream()
        val bytes = ArrayList<Byte>()
        while (true) {
            val value = input.read()
            if (value == -1) break
            bytes += value.toByte()
            if (bytes.takeLast(4).toByteArray().contentEquals("\r\n\r\n".toByteArray())) break
        }
        val headers = bytes.toByteArray().toString(Charsets.ISO_8859_1)
        val length = Regex("(?im)^Content-Length:\\s*(\\d+)").find(headers)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        repeat(length) {
            val value = input.read()
            if (value >= 0) bytes += value.toByte()
        }
        return bytes.toByteArray().toString(Charsets.ISO_8859_1)
    }
}
