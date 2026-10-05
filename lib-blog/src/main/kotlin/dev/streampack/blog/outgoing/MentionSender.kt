/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.outgoing

import dev.streampack.core.fetch.FetchException
import dev.streampack.core.fetch.FetchFailed
import dev.streampack.core.fetch.FetchRefused
import dev.streampack.core.fetch.FetchResponse
import dev.streampack.core.fetch.GuardedFetcher
import java.net.URI
import java.net.URLEncoder
import org.jsoup.Jsoup
import org.springframework.stereotype.Service

/**
 * Tells a linked page that a post mentioned it (#112): by Webmention when the page names an
 * endpoint, otherwise by Pingback when it names a server, otherwise not at all. Everything goes
 * through the [GuardedFetcher], so a post's links never reach anything that isn't public; a fetch
 * that fails on the way (a timeout, a 5xx) is tried again, [OutgoingLinksProperties.retries] times.
 */
@Service
class MentionSender(
    private val fetcher: GuardedFetcher,
    private val properties: OutgoingLinksProperties,
) {
    /** How a mention of one target went, as the log line says it. */
    data class Result(val text: String)

    /** Mentions [target] from [source]. Never throws: what happened is in the result. */
    fun mention(source: String, target: String): Result =
        try {
            val page = retrying { fetcher.get(target, headers(ACCEPT_HTML)) }
            val endpoint = webmentionEndpoint(page)
            val pingback = if (endpoint == null) pingbackServer(page) else null
            when {
                endpoint != null -> Result(webmention(endpoint, source, target))
                pingback != null -> Result(pingback(pingback, source, target))
                else -> Result("no endpoint")
            }
        } catch (e: FetchRefused) {
            Result("refused: ${e.message}")
        } catch (e: FetchException) {
            Result("failed: ${e.message}")
        }

    private fun webmention(endpoint: String, source: String, target: String): String {
        val form = "source=${encode(source)}&target=${encode(target)}"
        val response = retrying {
            fetcher.post(endpoint, "application/x-www-form-urlencoded", form, headers(null))
        }
        return "webmention ${response.status}"
    }

    private fun pingback(server: String, source: String, target: String): String {
        val call =
            """<?xml version="1.0"?><methodCall><methodName>pingback.ping</methodName><params>""" +
                "<param><value><string>${xml(source)}</string></value></param>" +
                "<param><value><string>${xml(target)}</string></value></param>" +
                "</params></methodCall>"
        val response = retrying { fetcher.post(server, "text/xml", call, headers(null)) }
        if (!response.successful) return "pingback ${response.status}"
        val fault = FAULT_CODE.find(response.body)?.groupValues?.get(1)
        return when {
            fault != null -> "pingback fault $fault"
            response.body.contains("<fault>") -> "pingback fault"
            else -> "pingback ok"
        }
    }

    /**
     * The Webmention endpoint [page] names, per the spec: a `Link` header with rel `webmention`
     * first, then the first `<link>` or `<a>` with that rel; relative to the page's address after
     * redirects (an empty one is the page itself).
     */
    fun webmentionEndpoint(page: FetchResponse): String? {
        linkHeader(page, "webmention")?.let {
            return it
        }
        if (!looksLikeHtml(page)) return null
        val element =
            Jsoup.parse(page.body, page.finalUri.toString())
                .select("link[href], a[href]")
                .firstOrNull { el ->
                    el.attr("rel").split(WHITESPACE).any { it.equals("webmention", true) }
                } ?: return null
        val href = element.attr("href")
        return if (href.isEmpty()) page.finalUri.toString() else resolve(page.finalUri, href)
    }

    /** The Pingback server [page] names: an `X-Pingback` header, else `<link rel="pingback">`. */
    fun pingbackServer(page: FetchResponse): String? {
        page
            .header("X-Pingback")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let {
                return resolve(page.finalUri, it)
            }
        if (!looksLikeHtml(page)) return null
        return Jsoup.parse(page.body, page.finalUri.toString())
            .select("link[href]")
            .firstOrNull { el ->
                el.attr("rel").split(WHITESPACE).any { it.equals("pingback", true) }
            }
            ?.attr("href")
            ?.takeIf { it.isNotBlank() }
            ?.let { resolve(page.finalUri, it) }
    }

    private fun linkHeader(page: FetchResponse, rel: String): String? =
        page
            .headers("Link")
            .flatMap { splitLinkHeader(it) }
            .firstOrNull { (_, rels) -> rels.any { it.equals(rel, ignoreCase = true) } }
            ?.let { (href, _) -> resolve(page.finalUri, href) }

    /** `<a>; rel="x y", <b>; rel=z` as (a, [x, y]), (b, [z]). */
    private fun splitLinkHeader(value: String): List<Pair<String, List<String>>> =
        LINK_VALUE.findAll(value)
            .map { m ->
                val rels =
                    REL_PARAM.find(m.groupValues[2])?.let { r ->
                        (r.groupValues[1].ifEmpty { r.groupValues[2] }).split(WHITESPACE)
                    } ?: emptyList()
                m.groupValues[1].trim() to rels
            }
            .toList()

    private fun looksLikeHtml(page: FetchResponse): Boolean {
        val type = page.header("Content-Type")?.lowercase() ?: return true
        return "html" in type
    }

    private fun resolve(base: URI, href: String): String = runCatching {
        base.resolve(href.trim()).toString()
    }
        .getOrDefault(href.trim())

    /**
     * [call], tried again after a failure on the way or a server error,
     * [OutgoingLinksProperties.retries] times, waiting [OutgoingLinksProperties.retryDelay] and
     * then twice as long each time.
     */
    private fun retrying(call: () -> FetchResponse): FetchResponse {
        var delay = properties.retryDelay
        var attempt = 0
        while (true) {
            val response =
                try {
                    call()
                } catch (e: FetchFailed) {
                    if (attempt >= properties.retries) throw e
                    null
                }
            if (response != null && (response.status < 500 || attempt >= properties.retries)) {
                return response
            }
            attempt++
            if (!delay.isZero) Thread.sleep(delay.toMillis())
            delay = delay.multipliedBy(2)
        }
    }

    private fun headers(accept: String?): Map<String, String> = buildMap {
        put("User-Agent", properties.userAgent)
        accept?.let { put("Accept", it) }
    }

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8)

    private fun xml(value: String) =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private companion object {
        const val ACCEPT_HTML = "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5"
        val WHITESPACE = Regex("\\s+")
        val LINK_VALUE = Regex("""<([^>]*)>\s*((?:;\s*[^;,]+)*)""")
        val REL_PARAM = Regex("""(?i)rel\s*=\s*(?:"([^"]*)"|([^\s;,"]+))""")
        val FAULT_CODE =
            Regex("""(?s)<name>\s*faultCode\s*</name>\s*<value>\s*(?:<int>|<i4>)?\s*(-?\d+)""")
    }
}
