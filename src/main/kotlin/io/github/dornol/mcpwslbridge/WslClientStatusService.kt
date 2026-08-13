package io.github.dornol.mcpwslbridge

import java.util.concurrent.ConcurrentHashMap

data class WslClientStatus(
    val installed: Boolean,
    val checkedAt: Long,
)

/** Cached availability checks shared by the settings UI and automatic refresh. */
class WslClientStatusService(
    private val availabilityChecker: (String, String) -> Boolean = { distro, client ->
        WslClientConfigurator.isCommandAvailable(distro, client)
    },
    private val cacheMillis: Long = 60_000L,
) {
    private val cache = ConcurrentHashMap<String, WslClientStatus>()

    fun status(distro: String, client: String, forceRefresh: Boolean = false): WslClientStatus {
        val key = "$client|$distro"
        val now = System.currentTimeMillis()
        cache[key]?.takeIf { !forceRefresh && now - it.checkedAt < cacheMillis }?.let { return it }
        return WslClientStatus(availabilityChecker(distro, client), now).also { cache[key] = it }
    }

    fun isInstalled(distro: String, client: String): Boolean = status(distro, client).installed

    fun clear() = cache.clear()
}
