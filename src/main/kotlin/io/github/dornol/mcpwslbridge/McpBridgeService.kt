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
    private val wslDistributionsProvider: () -> List<String> = { WslClientConfigurator.distributions() },
    private val experimentalHttpProxyEnabled: Boolean = true,
) : Disposable {
    private val log = Logger.getInstance(McpBridgeService::class.java)
    private val settings get() = settingsProvider()
    private val listeners = ConcurrentHashMap<String, ServerSocket>()
    private val ioExecutor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "MCP WSL Bridge I/O").apply { isDaemon = true }
    }
    private val experimentalHttpProxy = if (experimentalHttpProxyEnabled) ExperimentalHttpReverseProxy(ioExecutor) else null
    private val tcpRelay = McpTcpRelay(
        ioExecutor,
        { activeTarget },
        {
            settings.snapshot().let { state ->
                state.authToken.takeIf { state.authEnabled && it.isNotBlank() }
            }
        },
    )
    private val refreshExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "MCP WSL Bridge refresh").apply { isDaemon = true }
    }
    private val refreshGeneration = AtomicLong()
    private val statusListeners = CopyOnWriteArrayList<(Status) -> Unit>()

    @Volatile private var activeTarget: McpTarget? = null
    @Volatile private var activeRoutes: List<McpRoute> = emptyList()
    @Volatile private var lastError: String? = null
    @Volatile private var boundPort: Int? = null
    @Volatile private var lastSuccessfulRefreshTime: Long? = null
    @Volatile private var currentStatus: Status? = null
    @Volatile private var lastLoggedTarget: String? = null
    @Volatile private var ensuredWslProxy: String? = null
    @Volatile private var experimentalProxyIdentity: String? = null
    private val autoConfiguringClients = ConcurrentHashMap.newKeySet<String>()
    private val autoConfiguredEndpoints = ConcurrentHashMap<String, String>()
    private val autoRetryAt = ConcurrentHashMap<String, Long>()
    private val autoRetryDelay = ConcurrentHashMap<String, Long>()
    private val wslClientStatus = WslClientStatusService()

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
        val routes: List<RouteStatus> = emptyList(),
    )

    data class RouteStatus(
        val id: String,
        val displayName: String,
        val publicPath: String,
        val target: McpTarget?,
        val error: String? = null,
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
            activeRoutes = emptyList()
            lastError = null
            lastLoggedTarget = null
            publishStatus(snapshot)
            return
        }

        val profiles = settings.serverProfiles().filter { it.enabled }
        val resolvedRoutes = profiles.mapNotNull { profile ->
            val target = targetResolver.resolve(profile) ?: return@mapNotNull null
            runCatching {
                McpRoute(profile.publicPath, target, profile.targetPath)
            }.getOrElse { error ->
                log.warn("Invalid MCP route '${profile.id}': ${error.message}")
                null
            }
        }
        if (resolvedRoutes.isEmpty()) {
            if (listeners.isEmpty()) {
                stopListeners()
                activeTarget = null
                activeRoutes = emptyList()
            }
            lastError = "JetBrains MCP server was not found. Enable it in Settings | Tools | MCP Server. ${targetResolver.diagnostic()}"
            log.warn(lastError)
            lastLoggedTarget = null
            publishStatus(snapshot)
            return
        }

        activeRoutes = resolvedRoutes
        activeTarget = resolvedRoutes.first().target
        val targetDescription = resolvedRoutes.joinToString { "${it.target.host}:${it.target.port} (${it.target.source})" }
        if (lastLoggedTarget != targetDescription) {
            log.info("Resolved MCP targets: $targetDescription")
            lastLoggedTarget = targetDescription
        }
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
        val proxyIdentity = requestedAddresses.sorted().joinToString() + "|" + snapshot.listenerPort + "|" +
            snapshot.authEnabled + "|" + snapshot.authToken + "|" +
            resolvedRoutes.joinToString { "${it.publicPath}:${it.target.host}:${it.target.port}:${it.targetPath}" }
        if (listeners.isNotEmpty() && experimentalProxyIdentity != proxyIdentity) {
            experimentalHttpProxy?.stop()
            requestedAddresses.forEach { address ->
                    runCatching {
                        experimentalHttpProxy?.start(
                            address,
                            snapshot.listenerPort,
                            resolvedRoutes,
                            snapshot.authToken.takeIf { snapshot.authEnabled && it.isNotBlank() },
                        )
                    }
                    .onFailure { log.info("Experimental HTTP reverse proxy is unavailable: ${it.message}") }
            }
            experimentalProxyIdentity = proxyIdentity
        }
        if (listeners.isNotEmpty()) {
            lastError = null
            lastSuccessfulRefreshTime = System.currentTimeMillis()
            if (snapshot.autoRefreshClients) refreshConfiguredWslClients(snapshot)
        }
        publishStatus(snapshot)
    }

    private fun statusFor(snapshot: BridgeSettings.State): Status {
        val state = when {
            !snapshot.enabled -> State.DISABLED
            lastError?.startsWith("JetBrains MCP server was not found") == true -> State.STARTING
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
            routes = settings.serverProfiles().map { profile ->
                val active = activeRoutes.firstOrNull { it.publicPath == profile.publicPath }
                RouteStatus(profile.id, profile.displayName, profile.publicPath, active?.target,
                    if (profile.enabled && active == null) "MCP target was not found" else null)
            },
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
        val address = snapshot.endpointAddress.takeIf { listeners.containsKey(it) } ?: listeners.keys.firstOrNull() ?: return
        val availableDistros = wslDistributionsProvider().toSet()
        if (availableDistros.isEmpty()) {
            log.info("Skipping automatic WSL client refresh because no WSL distributions were found.")
            return
        }
        val configuredRoutes = activeRoutes.map { route ->
            val profile = settings.serverProfiles().firstOrNull { it.publicPath == route.publicPath }
            val serverName = profile?.let { WslClientConfigurator.serverNameForRoute(it.id) }
                ?: route.publicPath.trim('/').replace('/', '-')
            serverName to McpEndpoint.url(address, snapshot.listenerPort, route.publicPath, snapshot)
        }
        val codexDistros = snapshot.configuredCodexDistros.toMutableSet().apply {
            if (snapshot.codexConfigured && snapshot.wslDistro.isNotBlank()) add(snapshot.wslDistro)
        }.filterTo(mutableSetOf(), availableDistros::contains)
        val claudeDistros = snapshot.configuredClaudeDistros.toMutableSet().apply {
            if (snapshot.claudeConfigured && snapshot.wslDistro.isNotBlank()) add(snapshot.wslDistro)
        }.filterTo(mutableSetOf(), availableDistros::contains)
        val copilotDistros = snapshot.configuredCopilotDistros.filterTo(mutableSetOf(), availableDistros::contains)
        val clients = codexDistros.map { "codex|$it" } +
            claudeDistros.map { "claude|$it" } +
            copilotDistros.map { "copilot|$it" }
        val now = System.currentTimeMillis()
        clients.forEach { clientIdentity ->
            val separator = clientIdentity.indexOf('|')
            val client = clientIdentity.substring(0, separator)
            val distro = clientIdentity.substring(separator + 1)
            configuredRoutes.forEach { (serverName, endpoint) ->
                val routeIdentity = "$clientIdentity|$serverName"
                if (autoConfiguredEndpoints[routeIdentity] == endpoint ||
                    (autoRetryAt[routeIdentity] ?: 0L) > now ||
                    !autoConfiguringClients.add(routeIdentity)
                ) return@forEach
                ioExecutor.submit {
                    if (!wslClientStatus.isInstalled(distro, client)) {
                        log.info("Skipping automatic $client MCP refresh in WSL '$distro': '$client' is not installed or is not on PATH.")
                        autoRetryAt[routeIdentity] = System.currentTimeMillis() + CLIENT_AVAILABILITY_RETRY_MILLIS
                        autoConfiguringClients.remove(routeIdentity)
                        return@submit
                    }
                    val result = when (client) {
                        "codex" -> WslClientConfigurator.configureCodex(distro, endpoint, serverName)
                        "copilot" -> WslClientConfigurator.configureCopilotCli(distro, endpoint, serverName)
                        else -> WslClientConfigurator.configureClaudeCode(distro, endpoint, serverName)
                    }
                    if (result.succeeded) {
                        autoConfiguredEndpoints[routeIdentity] = endpoint
                        autoRetryAt.remove(routeIdentity)
                        autoRetryDelay.remove(routeIdentity)
                        log.info("Updated $client MCP endpoint '$serverName' in WSL '$distro' to $endpoint")
                    } else {
                        val delay = autoRetryDelay[routeIdentity] ?: INITIAL_AUTO_RETRY_MILLIS
                        autoRetryAt[routeIdentity] = System.currentTimeMillis() + delay
                        autoRetryDelay[routeIdentity] = (delay * 2).coerceAtMost(MAX_AUTO_RETRY_MILLIS)
                        log.info("Could not update $client MCP endpoint '$serverName' in WSL '$distro': ${result.output}")
                    }
                    autoConfiguringClients.remove(routeIdentity)
                }
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
        private const val INITIAL_AUTO_RETRY_MILLIS = 30_000L
        private const val MAX_AUTO_RETRY_MILLIS = 5 * 60_000L
        private const val CLIENT_AVAILABILITY_RETRY_MILLIS = 60_000L

        fun getInstance(): McpBridgeService = ApplicationManager.getApplication().getService(McpBridgeService::class.java)
    }
}
