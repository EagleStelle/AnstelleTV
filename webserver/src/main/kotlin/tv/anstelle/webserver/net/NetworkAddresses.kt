package tv.anstelle.webserver.net

import java.net.Inet4Address
import java.net.NetworkInterface

/** Local addresses the server binds to. */
data class BindAddresses(
    /** Hosts to open listeners on, including loopback (used by `adb forward`). */
    val hosts: List<String>,
    /** LAN addresses to show to the user. */
    val lanHosts: List<String>,
)

object NetworkAddresses {
    private const val LOOPBACK = "127.0.0.1"

    /**
     * IPv4 LAN addresses of interfaces that are up, plus loopback. Binding to these instead
     * of `0.0.0.0` keeps the server off VPN tunnels and mobile data interfaces.
     */
    fun current(): BindAddresses {
        val lan = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback && !it.isPointToPoint }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { LanGuard.isLanAddress(it) && !it.isLinkLocalAddress }
                .mapNotNull { it.hostAddress }
                .distinct()
                .sorted()
        }.getOrDefault(emptyList())
        return BindAddresses(hosts = lan + LOOPBACK, lanHosts = lan)
    }
}
