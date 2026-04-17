/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.service

import com.rometools.rome.feed.synd.SyndFeed
import com.rometools.rome.io.SyndFeedInput
import dev.streampack.rss.config.RssProperties
import dev.streampack.rss.model.DiscoveryResult
import java.io.StringReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.LinkedHashSet
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Discovers RSS or Atom feeds from a URL.
 *
 * The service supports three broad cases:
 * 1. the URL is already a feed URL and can be parsed directly
 * 2. the URL is an HTML page with standard head metadata such as `<link rel="alternate"
 *    type="application/rss+xml" ...>`
 * 3. the URL is an HTML page that exposes a credible feed link through body content or common
 *    feed-path conventions
 *
 * Discovery is intentionally pragmatic rather than specification-pure. Real sites are often only
 * partially correct:
 * - some expose feed links through `rel=alternate`
 * - some only link feeds in navigation or page body text
 * - some misreport content type or status code
 * - some publish feed URLs at common paths such as `/feed.xml` or `/index.xml`
 *
 * This service prefers high-confidence discovery first, then falls back to looser heuristics.
 *
 * Discovery order:
 * 1. fetch the URL body
 * 2. attempt direct feed parsing
 * 3. inspect HTML for standard alternate feed links
 * 4. inspect HTML anchors/links for feed-like hrefs or strong surrounding feed wording
 * 5. try common root-level feed candidate paths
 *
 * Any candidate URL found during discovery is fetched and parsed before it is accepted.
 */
@Service
class FeedDiscoveryService(private val properties: RssProperties) {

    private val logger = LoggerFactory.getLogger(FeedDiscoveryService::class.java)

    /**
     * Fetches and parses a known feed URL directly.
     *
     * Unlike [discover], this method does not perform HTML fallback discovery. It is intended for
     * situations where the caller already knows the concrete feed URL and simply wants to fetch and
     * parse it.
     *
     * @param feedUrl concrete RSS or Atom feed URL
     * @return the parsed feed, or `null` if the fetch or parse fails
     */
    fun fetchFeed(feedUrl: String): SyndFeed? {
        val body = fetchBody(feedUrl) ?: return null
        return tryParseFeed(feedUrl, body)
    }

    /**
     * Discovers a feed from an arbitrary URL.
     *
     * The URL may already point at a feed, or it may point at an HTML page that references one.
     * Direct parsing is attempted first. If that fails, HTML-based discovery heuristics are used.
     *
     * @param url arbitrary URL that may be either a feed URL or an HTML page containing feed hints
     * @return a [DiscoveryResult] containing the discovered feed URL and parsed feed, or `null`
     *   when no credible feed can be found
     */
    fun discover(url: String): DiscoveryResult? {
        val body = fetchBody(url)
        if (body == null) {
            logger.debug("Discovery failed: no response body from {}", url)
            return null
        }

        logger.debug("Discovery fetched {} bytes from {}", body.length, url)

        // Try direct ROME parse
        val directFeed = tryParseFeed(url, body)
        if (directFeed != null) {
            logger.debug("Direct parse succeeded for {} (title: {})", url, directFeed.title)
            return DiscoveryResult(feedUrl = url, feed = directFeed)
        }

        // Fall back to HTML link discovery
        return discoverFromHtml(url, body)
    }

    private fun fetchBody(url: String): String? {
        val client =
            HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(properties.connectTimeoutSeconds.toLong()))
                .build()

        repeat(2) { attempt ->
            try {
                val request =
                    HttpRequest.newBuilder()
                        .uri(URI(url))
                        .timeout(Duration.ofSeconds(properties.readTimeoutSeconds.toLong()))
                        .header(
                            "User-Agent",
                            "Mozilla/5.0 (compatible; Nevet/1.0; +https://bytecode.news)",
                        )
                        .header(
                            "Accept",
                            "application/rss+xml, application/atom+xml, application/xml, text/xml, text/html, */*;q=0.8",
                        )
                        .GET()
                        .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                val contentType = response.headers().firstValue("content-type").orElse("(none)")
                val body = response.body()
                logger.debug(
                    "HTTP {} from {} (content-type: {}, body: {} bytes)",
                    response.statusCode(),
                    url,
                    contentType,
                    body?.length ?: 0,
                )
                if (response.statusCode() in 200..299) {
                    return body
                } else {
                    // Return body on non-2xx if it looks like XML; some servers misconfigure status
                    // codes
                    if (body != null && body.trimStart().startsWith("<?xml", ignoreCase = true)) {
                        logger.debug(
                            "Non-2xx response contains XML, attempting parse anyway for {}",
                            url,
                        )
                        return body
                    }
                    return null
                }
            } catch (e: Exception) {
                logger.debug("Failed to fetch {} on attempt {}: {}", url, attempt + 1, e.message)
                if (attempt == 0) {
                    Thread.sleep(100)
                }
            }
        }

        return null
    }

    /**
     * Attempts to parse the supplied body as RSS or Atom using ROME.
     *
     * @param url source URL used only for diagnostics
     * @param body candidate feed body
     * @return the parsed feed, or `null` when the body is not parseable as a feed
     */
    private fun tryParseFeed(url: String, body: String): com.rometools.rome.feed.synd.SyndFeed? {
        return try {
            val input = SyndFeedInput()
            input.isAllowDoctypes = true
            input.build(StringReader(body))
        } catch (e: Exception) {
            logger.debug("ROME parse failed for {} ({} bytes): {}", url, body.length, e.message)
            null
        }
    }

    /**
     * Attempts feed discovery from HTML.
     *
     * This applies progressively looser strategies:
     * 1. standard `rel=alternate` feed discovery
     * 2. href/body-text heuristics for likely feed links
     * 3. common root-level feed path guesses
     *
     * Every candidate found here is still fetched and parsed before being accepted as a feed.
     *
     * @param baseUrl source page URL used to resolve relative links
     * @param html HTML body to inspect
     * @return a discovered feed result, or `null` if no candidate successfully parses
     */
    private fun discoverFromHtml(baseUrl: String, html: String): DiscoveryResult? {
        val document = Jsoup.parse(html, baseUrl)
        // Standard feed discovery: rel=alternate links with feed-ish MIME types.
        val alternateFeedHrefs =
            document
                .select(
                    "link[rel~=alternate][type*=rss+xml], " +
                        "link[rel~=alternate][type*=atom+xml], " +
                        "link[rel~=alternate][type*=application/xml], " +
                        "link[rel~=alternate][type*=text/xml]"
                )
                .map { it.absUrl("href") }
                .filter { it.isNotBlank() }

        discoverFromCandidates(baseUrl, alternateFeedHrefs)?.let {
            return it
        }

        // Pragmatic fallback: many sites link feed URLs as plain anchors in nav/footer.
        val hintedHrefs =
            document
                .select("a[href], link[href]")
                .filter { element ->
                    val href = element.absUrl("href")
                    href.isNotBlank() &&
                        (FEED_HINT_REGEX.containsMatchIn(href) || hasBodyFeedHint(element))
                }
                .map { it.absUrl("href") }
        discoverFromCandidates(baseUrl, hintedHrefs)?.let {
            return it
        }

        // Last resort for root/domain URLs: try common feed paths.
        discoverFromCandidates(baseUrl, commonFeedCandidates(baseUrl))?.let {
            return it
        }

        logger.debug("No feed discovered from HTML/candidates for {}", baseUrl)
        return null
    }

    /**
     * Tries a list of candidate feed URLs in order until one fetches and parses successfully.
     *
     * Duplicates are removed while preserving the original order of first appearance.
     *
     * @param baseUrl source page URL used only for diagnostics
     * @param candidates ordered candidate URLs to test
     * @return the first successfully discovered feed, or `null` if no candidate parses
     */
    private fun discoverFromCandidates(
        baseUrl: String,
        candidates: List<String>,
    ): DiscoveryResult? {
        val ordered = LinkedHashSet(candidates)
        for (href in ordered) {
            val feedBody = fetchBody(href) ?: continue
            val feed = tryParseFeed(href, feedBody)
            if (feed != null) {
                logger.debug("Discovered feed candidate {} from {}", href, baseUrl)
                return DiscoveryResult(feedUrl = href, feed = feed)
            }
        }
        return null
    }

    /**
     * Generates a last-resort list of common root-level feed paths for a site.
     *
     * This intentionally uses the site root rather than the original page path. It is meant as a
     * broad fallback for sites that do not expose any in-page hints at all.
     */
    private fun commonFeedCandidates(baseUrl: String): List<String> {
        val uri = runCatching { URI(baseUrl) }.getOrNull() ?: return emptyList()
        val authority = uri.authority ?: return emptyList()
        val root = "${uri.scheme}://$authority"
        return listOf(
            "$root/feed.xml",
            "$root/rss.xml",
            "$root/atom.xml",
            "$root/index.xml",
            "$root/feed",
            "$root/rss",
            "$root/atom",
        )
    }

    /**
     * Returns `true` when a link element has strong body-language evidence that it points to a
     * feed.
     *
     * This heuristic is used for pages that do not expose standard head metadata but do say things
     * like:
     * - `This page is also available as an RSS feed`
     * - `Subscribe via Atom`
     *
     * The check intentionally uses both the anchor text and the immediate parent text so that
     * phrases surrounding the anchor can contribute signal even if the anchor itself only says
     * something generic like `this url`.
     */
    private fun hasBodyFeedHint(element: org.jsoup.nodes.Element): Boolean {
        val ownText = element.text()
        val parentText = element.parent()?.text().orEmpty()
        val combined = "$ownText $parentText"
        return BODY_FEED_HINT_REGEX.containsMatchIn(combined)
    }

    companion object {
        private val FEED_HINT_REGEX = Regex("(?i)(/|\\b)(feed|rss|atom)(\\.xml)?([/?#].*)?$")
        private val BODY_FEED_HINT_REGEX =
            Regex("(?i)\\b(rss|atom|feed|rss\\s+feed|atom\\s+feed)\\b")
    }
}
