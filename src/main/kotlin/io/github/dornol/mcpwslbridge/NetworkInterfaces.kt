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
            val suggested = isSuggested(nic.name, nic.displayName)
            nic.inetAddresses.asSequence()
                .filterIsInstance<Inet4Address>()
                .filter(::isUsableIpv4)
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

    internal fun isSuggested(interfaceName: String, displayName: String?): Boolean =
        interfaceName.contains("wsl", ignoreCase = true) || displayName.orEmpty().contains("wsl", ignoreCase = true)

    internal fun isUsableIpv4(address: Inet4Address): Boolean =
        !address.isLoopbackAddress && !address.isLinkLocalAddress
}
