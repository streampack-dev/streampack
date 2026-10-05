/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.fetch

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Decides whether an address may be fetched: http or https, to a host whose every resolved address
 * is public. Loopback, private ranges, link-local (the cloud metadata address among them),
 * multicast and unspecified addresses are refused, loopback and private ones unless
 * [FetchProperties] allows them.
 *
 * The check is on resolution, before connecting. A DNS server answering differently for the
 * connection than for the check isn't caught; closing that means connecting to the checked address.
 */
class AddressGuard(
    private val properties: FetchProperties,
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
) {
    /** Throws [FetchRefused] unless [uri] may be fetched. */
    fun check(uri: URI) {
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") throw FetchRefused(uri, "not http or https")
        val host = uri.host?.removePrefix("[")?.removeSuffix("]")
        if (host.isNullOrBlank()) throw FetchRefused(uri, "no host")
        val addresses =
            try {
                resolve(host)
            } catch (e: Exception) {
                throw FetchFailed(uri, "can't resolve $host: ${e.message}", e)
            }
        if (addresses.isEmpty()) throw FetchFailed(uri, "can't resolve $host")
        addresses
            .firstOrNull { !allowed(it) }
            ?.let {
                throw FetchRefused(uri, "${it.hostAddress} is not a public address")
            }
    }

    fun allowed(address: InetAddress): Boolean =
        when {
            address.isAnyLocalAddress -> false
            address.isMulticastAddress -> false
            address.isLinkLocalAddress -> false
            address.isLoopbackAddress -> properties.allowLoopback
            isPrivate(address) -> properties.allowPrivate
            else -> !isReserved(address)
        }

    private fun isPrivate(address: InetAddress): Boolean {
        if (address.isSiteLocalAddress) return true
        val b = address.address
        return when {
            // 100.64.0.0/10, shared address space (carrier-grade NAT)
            b.size == 4 -> b[0].toInt() and 0xFF == 100 && b[1].toInt() and 0xC0 == 64
            // fc00::/7, unique local
            address is Inet6Address -> b[0].toInt() and 0xFE == 0xFC
            else -> false
        }
    }

    /** 0.0.0.0/8 and the broadcast address, which aren't anywhere to fetch from. */
    private fun isReserved(address: InetAddress): Boolean {
        val b = address.address
        if (b.size != 4) return false
        return b[0].toInt() == 0 || b.all { it.toInt() and 0xFF == 255 }
    }
}
