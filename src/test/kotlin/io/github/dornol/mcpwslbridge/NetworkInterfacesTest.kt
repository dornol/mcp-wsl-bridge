package io.github.dornol.mcpwslbridge

import java.net.Inet4Address
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkInterfacesTest {
    @Test
    fun `WSL suggestion matches interface or display name case insensitively`() {
        assertTrue(NetworkInterfaces.isSuggested("vEthernet", "vEthernet (WSL)"))
        assertTrue(NetworkInterfaces.isSuggested("VETH-WSL", null))
        assertFalse(NetworkInterfaces.isSuggested("Ethernet", "Intel Ethernet"))
    }

    @Test
    fun `loopback and link local IPv4 addresses are excluded`() {
        val loopback = InetAddress.getByName("127.0.0.1") as Inet4Address
        val linkLocal = InetAddress.getByName("169.254.1.1") as Inet4Address
        val privateAddress = InetAddress.getByName("192.168.1.10") as Inet4Address

        assertFalse(NetworkInterfaces.isUsableIpv4(loopback))
        assertFalse(NetworkInterfaces.isUsableIpv4(linkLocal))
        assertTrue(NetworkInterfaces.isUsableIpv4(privateAddress))
    }
}
