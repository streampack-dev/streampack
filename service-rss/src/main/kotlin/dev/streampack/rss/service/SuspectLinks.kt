/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.service

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Links a feed shouldn't have (#205): a development or private address, such as a feed published
 * with its generator still set to http://localhost:3000. Judged by the link alone, with no request
 * and no DNS lookup; only logged, since the fix is the publisher's, never ours.
 */
object SuspectLinks {
    private val LOCAL_SUFFIXES = listOf(".localhost", ".local", ".internal", ".test")
    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    /** Why [url] looks wrong, or null when it looks like a public web address. */
    fun check(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return "not a URL"
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return "not http or https"
        val host = hostOf(uri)?.lowercase()?.removeSuffix(".") ?: return "no host"
        if (host == "localhost" || LOCAL_SUFFIXES.any { host.endsWith(it) }) return "a local host"
        return literal(host)?.let(::private)
    }

    /**
     * The warning for a feed whose links look wrong, or null when they all look fine: [items] are
     * its entries' links, [site] its channel's own.
     */
    fun warning(title: String, feedUrl: String, site: String?, items: List<String>): String? {
        val suspect = items.mapNotNull { link -> check(link)?.let { link to it } }
        val siteWhy = site?.let(::check)
        if (suspect.isEmpty() && siteWhy == null) return null
        val parts = buildList {
            suspect.firstOrNull()?.let { (link, why) ->
                add("${suspect.size} of ${items.size} item links look wrong, e.g. $link ($why)")
            }
            if (siteWhy != null) add("its site link looks wrong: $site ($siteWhy)")
        }
        return "Feed \"$title\" ($feedUrl): ${parts.joinToString("; ")}"
    }

    /**
     * [uri]'s host; from its authority when URI won't name one, as for a host with an underscore
     * (my_blog.example.com), which browsers open.
     */
    private fun hostOf(uri: URI): String? =
        uri.host
            ?: uri.rawAuthority
                ?.substringAfterLast('@')
                ?.let {
                    if (it.startsWith("[")) it.substringBefore(']') + "]"
                    else it.substringBefore(':')
                }
                ?.takeIf { it.isNotBlank() }

    /** [host] as an IP address when it's written as one; never looked up. */
    private fun literal(host: String): InetAddress? {
        val bare = host.removePrefix("[").removeSuffix("]")
        val v4 = IPV4.matches(bare)
        if (!v4 && ':' !in bare) return null
        // Out of range, a dotted name would be looked up as a host name; it's no address
        if (v4 && bare.split('.').any { it.toInt() > 255 }) return null
        return runCatching { InetAddress.getByName(bare) }.getOrNull()
    }

    private fun private(address: InetAddress): String? =
        when {
            address.isLoopbackAddress -> "a loopback address"
            address.isAnyLocalAddress -> "an unspecified address"
            address.isLinkLocalAddress -> "a link-local address"
            address.isSiteLocalAddress -> "a private address"
            address is Inet6Address && (address.address[0].toInt() and 0xfe) == 0xfc ->
                "a private address"
            else -> null
        }
}
