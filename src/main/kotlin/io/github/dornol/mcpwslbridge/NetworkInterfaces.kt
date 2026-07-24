package io.github.dornol.mcpwslbridge

import java.net.Inet4Address
import java.net.NetworkInterface

data class NetworkAddress(
    val interfaceName: String,
    val displayName: String,
    val address: String,
    val suggestedForWsl: Boolean,
) {
    val label: String
        get() = "$displayName ($interfaceName) — $address" + if (suggestedForWsl) "  [WSL suggested]" else ""
}

object NetworkInterfaces {
    fun availableIpv4Addresses(): List<NetworkAddress> {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        return interfaces
        .asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { nic ->
            val suggested = nic.name.contains("wsl", ignoreCase = true) ||
                (nic.displayName ?: "").contains("wsl", ignoreCase = true)
            nic.inetAddresses.asSequence()
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                .map { address ->
                    NetworkAddress(nic.name, nic.displayName ?: nic.name, address.hostAddress, suggested)
                }
        }
        .sortedWith(compareByDescending<NetworkAddress> { it.suggestedForWsl }.thenBy { it.displayName }.thenBy { it.address })
        .toList()
    }

    fun suggestedWslAddresses(): List<String> = availableIpv4Addresses()
        .filter { it.suggestedForWsl }
        .map { it.address }

    fun addressesForInterfaces(interfaceNames: Collection<String>): List<String> =
        availableIpv4Addresses()
            .filter { it.interfaceName in interfaceNames }
            .map { it.address }
}
