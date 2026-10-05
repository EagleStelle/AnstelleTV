package tv.anstelle.webserver.net

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Accepts connections only from the local network: loopback, private IPv4 (RFC 1918),
 * link-local, and IPv6 unique-local (fc00::/7). Everything else, including carrier-grade
 * NAT ranges and public addresses, is rejected. Only IP literals are parsed; host names
 * are never resolved.
 */
object LanGuard {
    fun isAllowed(remoteAddress: String?): Boolean {
        if (remoteAddress.isNullOrBlank()) return false
        if (remoteAddress == "localhost") return true
        val address = parseLiteral(remoteAddress) ?: return false
        return isLanAddress(address)
    }

    fun isLanAddress(address: InetAddress): Boolean = when {
        address.isLoopbackAddress -> true
        address.isLinkLocalAddress -> true
        address is Inet4Address -> address.isSiteLocalAddress
        address is Inet6Address -> isUniqueLocal(address) || isMappedLan(address)
        else -> false
    }

    private fun isUniqueLocal(address: Inet6Address): Boolean =
        (address.address[0].toInt() and 0xFE) == 0xFC

    /** `::ffff:192.168.1.5` style addresses. */
    private fun isMappedLan(address: Inet6Address): Boolean {
        val b = address.address
        for (i in 0 until 10) if (b[i].toInt() != 0) return false
        if (b[10].toInt() != -1 || b[11].toInt() != -1) return false
        val v4 = InetAddress.getByAddress(b.copyOfRange(12, 16))
        return v4.isLoopbackAddress || v4.isSiteLocalAddress || v4.isLinkLocalAddress
    }

    /** Parses an IPv4 or IPv6 literal without DNS. Returns null for host names. */
    private fun parseLiteral(text: String): InetAddress? {
        val host = text.removePrefix("[").removeSuffix("]").substringBefore('%')
        val looksLikeIp = host.contains(':') || host.all { it.isDigit() || it == '.' }
        if (!looksLikeIp) return null
        return runCatching { InetAddress.getByName(host) }.getOrNull()
    }
}
