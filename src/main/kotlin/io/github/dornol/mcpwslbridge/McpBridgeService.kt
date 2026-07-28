package io.github.dornol.mcpwslbridge

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@Service(Service.Level.APP)
class McpBridgeService(
    private val settingsProvider: () -> BridgeSettings = { BridgeSettings.getInstance() },
    private val targetResolver: McpTargetResolver = McpTargetResolver(),
    private val addressesProvider: (BridgeSettings.State) -> List<String> = { snapshot ->
        NetworkInterfaces.addressesForInterfaces(snapshot.selectedInterfaceNames)
            .ifEmpty { snapshot.selectedAddresses.ifEmpty { NetworkInterfaces.suggestedWslAddresses() } }
    },
    private val experimentalHttpProxyEnabled: Boolean = true,
) : Disposable {
    private val log = Logger.getInstance(McpBridgeService::class.java)
    private val settings get() = settingsProvider()
    private val listeners = ConcurrentHashMap<String, ServerSocket>()
    private val ioExecutor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "MCP WSL Bridge I/O").apply { isDaemon = true }
    }
    private val experimentalHttpProxy = if (experimentalHttpProxyEnabled) ExperimentalHttpReverseProxy(ioExecutor) else null
    private val tcpRelay = McpTcpRelay(ioExecutor) { activeTarget }
    private val refreshExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "MCP WSL Bridge refresh").apply { isDaemon = true }
    }
    private val refreshGeneration = AtomicLong()
    private val statusListeners = CopyOnWriteArrayList<(Status) -> Unit>()

    @Volatile private var activeTarget: McpTarget? = null
    @Volatile private var lastError: String? = null
    @Volatile private var boundPort: Int? = null
    @Volatile private var lastSuccessfulRefreshTime: Long? = null
    @Volatile private var currentStatus: Status? = null
    @Volatile private var ensuredWslProxy: String? = null
    @Volatile private var experimentalProxyIdentity: String? = null
    private val autoConfiguringClients = ConcurrentHashMap.newKeySet<String>()
    private val autoConfiguredEndpoints = ConcurrentHashMap<String, String>()

    init {
        scheduleRefresh(0)
    }

    enum class State { DISABLED, STARTING, CONNECTED, ERROR }

    data class Status(
        val runningAddresses: List<String>,
        val target: McpTarget?,
        val error: String?,
        val state: State,
        val listenerPort: Int,
        val lastSuccessfulRefreshTime: Long?,
    )

    fun status(): Status = currentStatus ?: statusFor(settings.snapshot())

    fun addStatusListener(listener: (Status) -> Unit): Disposable {
        statusListeners += listener
        listener(status())
        return Disposable { statusListeners -= listener }
    }

    fun restart(onComplete: (() -> Unit)? = null) {
        val generation = refreshGeneration.incrementAndGet()
        refreshExecutor.execute {
            try {
                refresh()
            } finally {
                scheduleRefresh(generation, nextRefreshDelay())
                onComplete?.invoke()
            }
        }
    }

    private fun scheduleRefresh(delaySeconds: Long) {
        scheduleRefresh(refreshGeneration.get(), delaySeconds)
    }

    private fun scheduleRefresh(generation: Long, delaySeconds: Long) {
        if (refreshExecutor.isShutdown) return
        refreshExecutor.schedule({
            if (generation != refreshGeneration.get()) return@schedule
            refresh()
            scheduleRefresh(generation, nextRefreshDelay())
        }, delaySeconds, TimeUnit.SECONDS)
    }

    private fun nextRefreshDelay(): Long = if (status().state == State.CONNECTED) 10 else 1

    private fun refresh() {
        val snapshot = settings.snapshot()
        if (!snapshot.enabled) {
            stopListeners()
            activeTarget = null
            lastError = null
            publishStatus(snapshot)
            return
        }

        val target = targetResolver.resolve(snapshot)
        if (target == null) {
            stopListeners()
            activeTarget = null
            lastError = "IntelliJ MCP server was not found. Enable it in Settings | Tools | MCP Server."
            publishStatus(snapshot)
            return
        }

        activeTarget = target
        val requestedAddresses = addressesProvider(snapshot).toSet()
        if (requestedAddresses.isEmpty()) {
            stopListeners()
            lastError = "No network interface is selected. Select a WSL NIC address in MCP WSL Bridge settings."
            publishStatus(snapshot)
            return
        }

        if (boundPort != null && boundPort != snapshot.listenerPort + 1) {
            stopListeners()
        }

        listeners.entries.filter { it.key !in requestedAddresses }.forEach { (address, socket) ->
            listeners.remove(address, socket)
            runCatching { socket.close() }
        }
        requestedAddresses.filter { !listeners.containsKey(it) }.forEach { bind(it, snapshot.listenerPort + 1) }
        val proxyIdentity = requestedAddresses.sorted().joinToString() + "|" + snapshot.listenerPort + "|" + target.host + "|" + target.port
        if (listeners.isNotEmpty() && experimentalProxyIdentity != proxyIdentity) {
            experimentalHttpProxy?.stop()
            requestedAddresses.forEach { address ->
                runCatching { experimentalHttpProxy?.start(address, snapshot.listenerPort, target) }
                    .onFailure { log.info("Experimental HTTP reverse proxy is unavailable: ${it.message}") }
            }
            experimentalProxyIdentity = proxyIdentity
        }
        if (listeners.isNotEmpty()) {
            lastError = null
            lastSuccessfulRefreshTime = System.currentTimeMillis()
            refreshConfiguredWslClients(snapshot)
        }
        publishStatus(snapshot)
    }

    private fun statusFor(snapshot: BridgeSettings.State): Status {
        val state = when {
            !snapshot.enabled -> State.DISABLED
            lastError?.startsWith("IntelliJ MCP server was not found") == true -> State.STARTING
            lastError != null -> State.ERROR
            listeners.isNotEmpty() && activeTarget != null -> State.CONNECTED
            else -> State.STARTING
        }
        return Status(
            runningAddresses = listeners.keys.sorted(),
            target = activeTarget,
            error = lastError,
            state = state,
            listenerPort = snapshot.listenerPort,
            lastSuccessfulRefreshTime = lastSuccessfulRefreshTime,
        )
    }

    private fun publishStatus(snapshot: BridgeSettings.State) {
        val next = statusFor(snapshot)
        if (next == currentStatus) return
        currentStatus = next
        statusListeners.forEach { listener ->
            runCatching { listener(next) }
                .onFailure { log.warn("MCP WSL Bridge status listener failed", it) }
        }
    }

    private fun refreshConfiguredWslClients(snapshot: BridgeSettings.State) {
        val address = listeners.keys.firstOrNull() ?: return
        val endpoint = "http://$address:${snapshot.listenerPort}/stream"
        val codexDistros = snapshot.configuredCodexDistros.toMutableSet().apply {
            if (snapshot.codexConfigured && snapshot.wslDistro.isNotBlank()) add(snapshot.wslDistro)
        }
        val claudeDistros = snapshot.configuredClaudeDistros.toMutableSet().apply {
            if (snapshot.claudeConfigured && snapshot.wslDistro.isNotBlank()) add(snapshot.wslDistro)
        }
        val copilotDistros = snapshot.configuredCopilotDistros
        val clients = codexDistros.map { "codex|$it" } +
            claudeDistros.map { "claude|$it" } +
            copilotDistros.map { "copilot|$it" }
        clients.forEach { clientIdentity ->
            val separator = clientIdentity.indexOf('|')
            val client = clientIdentity.substring(0, separator)
            val distro = clientIdentity.substring(separator + 1)
            if (autoConfiguredEndpoints[clientIdentity] == endpoint || !autoConfiguringClients.add(clientIdentity)) return@forEach
            ioExecutor.submit {
                val result = when (client) {
                    "codex" -> WslClientConfigurator.configureCodex(distro, endpoint)
                    "copilot" -> WslClientConfigurator.configureCopilotCli(distro, endpoint)
                    else -> WslClientConfigurator.configureClaudeCode(distro, endpoint)
                }
                if (result.succeeded) {
                    autoConfiguredEndpoints[clientIdentity] = endpoint
                    log.info("Updated $client MCP endpoint in WSL '$distro' to $endpoint")
                } else {
                    log.info("Could not update $client MCP endpoint in WSL '$distro': ${result.output}")
                }
                autoConfiguringClients.remove(clientIdentity)
            }
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
                ioExecutor.submit { tcpRelay.relay(client) }
            }
        } catch (error: IOException) {
            if (!listener.isClosed) {
                lastError = "Listener $address stopped: ${error.message}"
                log.warn(lastError, error)
            }
        } finally {
            listeners.remove(address, listener)
            runCatching { listener.close() }
            publishStatus(settings.snapshot())
        }
    }

    private fun stopListeners() {
        listeners.entries.forEach { (address, socket) ->
            listeners.remove(address, socket)
            runCatching { socket.close() }
        }
        boundPort = null
        ensuredWslProxy = null
        experimentalProxyIdentity = null
        experimentalHttpProxy?.stop()
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
        fun getInstance(): McpBridgeService = ApplicationManager.getApplication().getService(McpBridgeService::class.java)
    }
}
