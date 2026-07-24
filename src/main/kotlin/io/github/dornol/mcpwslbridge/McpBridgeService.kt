package io.github.dornol.mcpwslbridge

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

@Service(Service.Level.APP)
class McpBridgeService : Disposable {
    private val log = Logger.getInstance(McpBridgeService::class.java)
    private val settings get() = BridgeSettings.getInstance()
    private val targetResolver = McpTargetResolver()
    private val listeners = ConcurrentHashMap<String, ServerSocket>()
    private val ioExecutor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "MCP WSL Bridge I/O").apply { isDaemon = true }
    }
    private val refreshExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "MCP WSL Bridge refresh").apply { isDaemon = true }
    }

    @Volatile private var activeTarget: McpTarget? = null
    @Volatile private var lastError: String? = null
    @Volatile private var boundPort: Int? = null
    @Volatile private var ensuredWslProxy: String? = null

    init {
        refreshExecutor.scheduleWithFixedDelay(::refresh, 0, 3, TimeUnit.SECONDS)
    }

    data class Status(
        val runningAddresses: List<String>,
        val target: McpTarget?,
        val error: String?,
    )

    fun status(): Status = Status(listeners.keys.sorted(), activeTarget, lastError)

    fun restart() = refresh()

    private fun refresh() {
        val snapshot = settings.snapshot()
        if (!snapshot.enabled) {
            stopListeners()
            activeTarget = null
            lastError = null
            return
        }

        val target = targetResolver.resolve(snapshot)
        if (target == null) {
            stopListeners()
            activeTarget = null
            lastError = "IntelliJ MCP server was not found. Enable it in Settings | Tools | MCP Server."
            return
        }

        activeTarget = target
        val requestedAddresses = NetworkInterfaces.addressesForInterfaces(snapshot.selectedInterfaceNames)
            .ifEmpty { snapshot.selectedAddresses.ifEmpty { NetworkInterfaces.suggestedWslAddresses() } }
            .toSet()
        if (requestedAddresses.isEmpty()) {
            stopListeners()
            lastError = "No network interface is selected. Select a WSL NIC address in MCP WSL Bridge settings."
            return
        }

        if (boundPort != null && boundPort != snapshot.listenerPort) {
            stopListeners()
        }

        listeners.entries.filter { it.key !in requestedAddresses }.forEach { (address, socket) ->
            listeners.remove(address, socket)
            runCatching { socket.close() }
        }
        requestedAddresses.filter { !listeners.containsKey(it) }.forEach { bind(it, snapshot.listenerPort) }
        if (listeners.isNotEmpty()) {
            lastError = null
            ensureWslLoopbackProxy(snapshot)
        }
    }

    private fun bind(address: String, port: Int) {
        runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName(address), port))
            }
        }.onSuccess { socket ->
            listeners[address] = socket
            boundPort = port
            ioExecutor.submit { acceptLoop(address, socket) }
        }.onFailure { error ->
            lastError = "Cannot bind $address:$port — ${error.message}"
            log.warn(lastError, error)
        }
    }

    private fun acceptLoop(address: String, listener: ServerSocket) {
        try {
            while (!listener.isClosed) {
                val client = listener.accept()
                ioExecutor.submit { relay(client) }
            }
        } catch (error: IOException) {
            if (!listener.isClosed) {
                lastError = "Listener $address stopped: ${error.message}"
                log.warn(lastError, error)
            }
        } finally {
            listeners.remove(address, listener)
            runCatching { listener.close() }
        }
    }

    private fun relay(client: Socket) {
        val target = activeTarget ?: return client.close()
        try {
            client.use { incoming ->
                Socket().use { outgoing ->
                    outgoing.connect(InetSocketAddress(target.host, target.port), CONNECT_TIMEOUT_MS)
                    val clientInput = forwardInitialRequest(incoming.getInputStream(), outgoing.getOutputStream(), target)
                    val clientToServer = copyAsync(clientInput, outgoing.getOutputStream()) { outgoing.shutdownOutput() }
                    val serverToClient = copyAsync(outgoing.getInputStream(), incoming.getOutputStream()) { incoming.shutdownOutput() }
                    waitForEither(incoming, outgoing, clientToServer, serverToClient)
                }
            }
        } catch (error: IOException) {
            log.debug("MCP proxy connection closed: ${error.message}")
        }
    }

    /**
     * IntelliJ's MCP server accepts loopback hosts only. The TCP listener is intentionally exposed
     * on a WSL adapter, so an HTTP client sends that adapter in its Host and sometimes Origin
     * headers. Replace both before relaying the stream; SSE and HTTP/1.1 WebSocket upgrades remain
     * byte-for-byte after the initial request headers.
     */
    private fun forwardInitialRequest(input: InputStream, output: OutputStream, target: McpTarget): InputStream {
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
        if (matchedTerminatorBytes != HTTP_HEADER_TERMINATOR.size || !rawHeaders.startsWith("GET ") && !rawHeaders.startsWith("POST ")) {
            output.write(headerBytes.toByteArray())
        } else {
            val rewrittenHeaders = rawHeaders
                .removeSuffix("\r\n\r\n")
                .lineSequence()
                .filterNot {
                    it.startsWith("Host:", ignoreCase = true) ||
                        it.startsWith("Origin:", ignoreCase = true)
                }
                .joinToString("\r\n")
            output.write(
                (
                    "$rewrittenHeaders\r\nHost: ${target.host}:${target.port}\r\n" +
                        "Origin: http://${target.host}:${target.port}\r\n\r\n"
                    ).toByteArray(HTTP_HEADER_CHARSET),
            )
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
            while (!first.isDone && !second.isDone) {
                Thread.sleep(25)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            runCatching { firstSocket.close() }
            runCatching { secondSocket.close() }
            first.cancel(true)
            second.cancel(true)
        }
    }

    private fun stopListeners() {
        listeners.entries.forEach { (address, socket) ->
            listeners.remove(address, socket)
            runCatching { socket.close() }
        }
        boundPort = null
        ensuredWslProxy = null
    }

    private fun ensureWslLoopbackProxy(snapshot: BridgeSettings.State) {
        val distro = snapshot.wslDistro
        val address = listeners.keys.firstOrNull() ?: return
        val endpoint = "http://$address:${snapshot.listenerPort}/stream"
        val identity = "$distro|$endpoint"
        if (distro.isBlank() || ensuredWslProxy == identity) return
        ensuredWslProxy = identity
        ioExecutor.submit {
            val configured = WslClientConfigurator.ensureLoopbackProxy(distro, endpoint)
            if (configured == null) {
                ensuredWslProxy = null
                log.info("WSL loopback proxy could not be started for '$distro'.")
            }
        }
    }

    override fun dispose() {
        stopListeners()
        refreshExecutor.shutdownNow()
        ioExecutor.shutdownNow()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val MAX_HTTP_HEADER_BYTES = 64 * 1024
        private val HTTP_HEADER_TERMINATOR = intArrayOf('\r'.code, '\n'.code, '\r'.code, '\n'.code)
        private val HTTP_HEADER_CHARSET = Charsets.ISO_8859_1

        fun getInstance(): McpBridgeService = ApplicationManager.getApplication().getService(McpBridgeService::class.java)
    }
}
