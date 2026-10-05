package tv.anstelle.webserver

import tv.anstelle.webserver.net.LanGuard
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanGuardTest {
    @Test
    fun allowsLocalNetworkAddresses() {
        for (address in listOf(
            "127.0.0.1", "localhost", "10.0.2.2", "172.16.5.4", "172.31.255.1", "192.168.1.10",
            "169.254.10.20", "::1", "fd12:3456:789a::1", "fe80::1%wlan0", "::ffff:192.168.1.5",
        )) {
            assertTrue(LanGuard.isAllowed(address), address)
        }
    }

    @Test
    fun rejectsPublicAndUnknownAddresses() {
        for (address in listOf(
            "8.8.8.8", "100.64.0.1", "172.32.0.1", "192.169.0.1", "2001:4860:4860::8888",
            "::ffff:8.8.8.8", "example.com", "", null,
        )) {
            assertFalse(LanGuard.isAllowed(address), address.toString())
        }
    }
}
