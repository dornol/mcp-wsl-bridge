package io.github.dornol.mcpwslbridge

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.io.IOException
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
        val requestedAddresses = snapshot.selectedAddresses.ifEmpty { NetworkInterfaces.suggestedWslAddresses() }.toSet()
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
        if (listeners.isNotEmpty()) lastError = null
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
                    val clientToServer = copyAsync(incoming, outgoing)
                    val serverToClient = copyAsync(outgoing, incoming)
                    waitForEither(incoming, outgoing, clientToServer, serverToClient)
                }
            }
        } catch (error: IOException) {
            log.debug("MCP proxy connection closed: ${error.message}")
        }
    }

    private fun copyAsync(from: Socket, to: Socket): Future<*> = ioExecutor.submit {
        try {
            from.getInputStream().copyTo(to.getOutputStream())
            to.getOutputStream().flush()
        } finally {
            runCatching { to.shutdownOutput() }
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
    }

    override fun dispose() {
        stopListeners()
        refreshExecutor.shutdownNow()
        ioExecutor.shutdownNow()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 3_000

        fun getInstance(): McpBridgeService = ApplicationManager.getApplication().getService(McpBridgeService::class.java)
    }
}
