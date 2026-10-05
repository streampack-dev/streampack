/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.outgoing

import java.net.URI
import org.jsoup.Jsoup

/** The links in a post that lead off the site (#112, #128). */
object OutgoingLinks {
    /**
     * The absolute http(s) links in [html] to hosts other than [ownHost] and its subdomains, each
     * once, in order, without fragments.
     */
    fun extract(html: String, ownHost: String?): List<String> {
        val own = ownHost?.lowercase()?.removePrefix("www.")
        return Jsoup.parse(html)
            .select("a[href]")
            .mapNotNull { a -> normalize(a.attr("href")) }
            .filter { url ->
                val host = host(url) ?: return@filter false
                own == null || (host != own && !host.endsWith(".$own"))
            }
            .distinct()
    }

    /**
     * The host of [url], lower case, without `www.`; null for anything that isn't a web address.
     */
    fun host(url: String): String? = runCatching {
        URI(url).host
    }
        .getOrNull()
        ?.lowercase()
        ?.removePrefix("www.")

    private fun normalize(href: String): String? {
        val uri = runCatching { URI(href.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if ((scheme != "http" && scheme != "https") || uri.host.isNullOrBlank()) return null
        // Rebuilt without the fragment, keeping the original escaping.
        return buildString {
            append(scheme).append("://").append(uri.rawAuthority)
            append(uri.rawPath.orEmpty())
            uri.rawQuery?.let { append('?').append(it) }
        }
    }
}
