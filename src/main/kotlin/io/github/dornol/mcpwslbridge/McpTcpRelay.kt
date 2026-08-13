package io.github.dornol.mcpwslbridge

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

class McpTcpRelay(
    private val ioExecutor: ExecutorService,
    private val targetProvider: () -> McpTarget?,
    private val authTokenProvider: () -> String? = { null },
) {
    fun relay(client: Socket) {
        val target = targetProvider() ?: return client.close()
        try {
            client.use { incoming ->
                Socket().use { outgoing ->
                    outgoing.connect(InetSocketAddress(target.host, target.port), CONNECT_TIMEOUT_MS)
                    val clientInput = forwardInitialRequest(incoming.getInputStream(), outgoing.getOutputStream(), target)
                        ?: return@use
                    val clientToServer = copyAsync(clientInput, outgoing.getOutputStream()) { outgoing.shutdownOutput() }
                    val serverToClient = copyAsync(outgoing.getInputStream(), incoming.getOutputStream()) { incoming.shutdownOutput() }
                    waitForEither(incoming, outgoing, clientToServer, serverToClient)
                }
            }
        } catch (_: IOException) {
            // Client disconnects are expected for streaming connections.
        }
    }

    private fun forwardInitialRequest(input: InputStream, output: OutputStream, target: McpTarget): InputStream? {
        val bufferedInput = BufferedInputStream(input)
        val headerBytes = ByteArrayOutputStream()
        var matchedTerminatorBytes = 0
        while (headerBytes.size() < MAX_HTTP_HEADER_BYTES) {
            val next = bufferedInput.read()
            if (next == -1) break
            headerBytes.write(next)
            matchedTerminatorBytes = when {
                next == HTTP_HEADER_TERMINATOR[matchedTerminatorBytes] -> matchedTerminatorBytes + 1
                next == HTTP_HEADER_TERMINATOR[0] -> 1
                else -> 0
            }
            if (matchedTerminatorBytes == HTTP_HEADER_TERMINATOR.size) break
        }
        val rawHeaders = headerBytes.toString(HTTP_HEADER_CHARSET)
        val expectedToken = authTokenProvider()
        if (expectedToken != null && queryToken(rawHeaders) != expectedToken) {
            output.write("HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n".toByteArray(HTTP_HEADER_CHARSET))
            output.flush()
            return null
        }
        if (matchedTerminatorBytes != HTTP_HEADER_TERMINATOR.size ||
            !rawHeaders.startsWith("GET ") && !rawHeaders.startsWith("POST ")
        ) {
            output.write(headerBytes.toByteArray())
        } else {
            val lines = rawHeaders.removeSuffix("\r\n\r\n").lineSequence().toList()
            val rewrittenRequestLine = stripTokenFromRequestLine(lines.first())
            val rewrittenHeaders = lines.drop(1)
                .filterNot { it.startsWith("Host:", true) || it.startsWith("Origin:", true) }
                .joinToString("\r\n")
            output.write(("$rewrittenRequestLine\r\n$rewrittenHeaders\r\nHost: ${target.host}:${target.port}\r\n" +
                "Origin: http://${target.host}:${target.port}\r\n\r\n").toByteArray(HTTP_HEADER_CHARSET))
        }
        output.flush()
        return bufferedInput
    }

    private fun copyAsync(from: InputStream, to: OutputStream, onComplete: () -> Unit): Future<*> = ioExecutor.submit {
        try {
            from.copyTo(to)
            to.flush()
        } finally {
            runCatching(onComplete)
        }
    }

    private fun waitForEither(firstSocket: Socket, secondSocket: Socket, first: Future<*>, second: Future<*>) {
        try {
            while (!first.isDone && !second.isDone) Thread.sleep(25)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            runCatching { firstSocket.close() }
            runCatching { secondSocket.close() }
            first.cancel(true)
            second.cancel(true)
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 3_000
        const val MAX_HTTP_HEADER_BYTES = 64 * 1024
        val HTTP_HEADER_TERMINATOR = intArrayOf('\r'.code, '\n'.code, '\r'.code, '\n'.code)
        val HTTP_HEADER_CHARSET = Charsets.ISO_8859_1

        fun queryToken(headers: String): String? =
            Regex("(?m)^[A-Z]+\\s+[^\\s?]*\\?[^\\s]*?token=([^&\\s]+)").find(headers)?.groupValues?.get(1)

        fun stripTokenFromRequestLine(requestLine: String): String {
            val parts = requestLine.split(' ', limit = 3)
            if (parts.size != 3) return requestLine
            val target = runCatching { java.net.URI(parts[1]) }.getOrNull() ?: return requestLine
            val query = target.rawQuery.orEmpty().split('&')
                .filter { it.isNotBlank() && !it.startsWith("token=") }
                .joinToString("&")
                .ifBlank { null }
            val path = target.rawPath.ifBlank { "/" } + query?.let { "?$it" }.orEmpty()
            return "${parts[0]} $path ${parts[2]}"
        }
    }
}
