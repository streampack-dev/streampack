/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.service

import com.rometools.rome.feed.synd.SyndFeed
import com.rometools.rome.io.SyndFeedInput
import dev.streampack.core.fetch.FetchException
import dev.streampack.core.fetch.FetchRefused
import dev.streampack.core.fetch.GuardedFetcher
import dev.streampack.rss.model.DiscoveryResult
import java.io.StringReader
import java.net.URI
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
class FeedDiscoveryService(private val fetcher: GuardedFetcher) {

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
    fun discover(url: String): DiscoveryResult? = discover(url, siteOnly = false)

    /**
     * Discovers the site feed of the site [url] is on, for autosubscribing (#128): as [discover],
     * but never a comments feed, and a feed found other than by standard discovery (a link that
     * looks like a feed, a common path) only if the feed's own site is [url]'s host.
     */
    fun discoverSiteFeed(url: String): DiscoveryResult? = discover(url, siteOnly = true)

    private fun discover(url: String, siteOnly: Boolean): DiscoveryResult? {
        val body = fetchBody(url)
        if (body == null) {
            logger.debug("Discovery failed: no response body from {}", url)
            return null
        }

        logger.debug("Discovery fetched {} bytes from {}", body.length, url)

        // Try direct ROME parse
        val directFeed = tryParseFeed(url, body)?.takeUnless { siteOnly && isCommentsFeed(url, it) }
        if (directFeed != null) {
            logger.debug("Direct parse succeeded for {} (title: {})", url, directFeed.title)
            return DiscoveryResult(feedUrl = url, feed = directFeed)
        }

        // Fall back to HTML link discovery
        return discoverFromHtml(url, body, siteOnly)
    }

    /**
     * The body at [url], through the guarded fetcher, so nothing but public addresses is fetched,
     * whoever named it. A non-2xx answer's body is kept when it looks like XML: some servers
     * misreport their status. One retry when the fetch itself fails.
     */
    private fun fetchBody(url: String): String? {
        repeat(2) { attempt ->
            try {
                val response = fetcher.get(url, HEADERS, MAX_FEED_BYTES)
                logger.debug(
                    "HTTP {} from {} (content-type: {}, body: {} bytes)",
                    response.status,
                    url,
                    response.header("content-type") ?: "(none)",
                    response.body.length,
                )
                if (response.successful) return response.body
                if (response.body.trimStart().startsWith("<?xml", ignoreCase = true)) {
                    logger.debug(
                        "Non-2xx response contains XML, attempting parse anyway for {}",
                        url,
                    )
                    return response.body
                }
                return null
            } catch (e: FetchRefused) {
                logger.info("Not fetching {}: {}", url, e.message)
                return null
            } catch (e: FetchException) {
                logger.debug("Failed to fetch {} on attempt {}: {}", url, attempt + 1, e.message)
                if (attempt == 0) Thread.sleep(100)
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
    private fun discoverFromHtml(
        baseUrl: String,
        html: String,
        siteOnly: Boolean,
    ): DiscoveryResult? {
        val document = Jsoup.parse(html, baseUrl)
        // Standard feed discovery: rel=alternate links with feed-ish MIME types. A site's own feed
        // before its comments feed (WordPress advertises both); for autosubscribing, never the
        // comments feed.
        val (comments, site) =
            document
                .select(
                    "link[rel~=alternate][type*=rss+xml], " +
                        "link[rel~=alternate][type*=atom+xml], " +
                        "link[rel~=alternate][type*=application/xml], " +
                        "link[rel~=alternate][type*=text/xml]"
                )
                .filter { it.absUrl("href").isNotBlank() }
                .partition { isCommentsLink(it.absUrl("href"), it.attr("title")) }
        val alternateFeedHrefs =
            (site + if (siteOnly) emptyList() else comments).map { it.absUrl("href") }

        discoverFromCandidates(baseUrl, alternateFeedHrefs, siteOnly, sameSite = false)?.let {
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
        discoverFromCandidates(baseUrl, hintedHrefs, siteOnly, sameSite = siteOnly)?.let {
            return it
        }

        // Last resort for root/domain URLs: try common feed paths.
        discoverFromCandidates(
                baseUrl,
                commonFeedCandidates(baseUrl),
                siteOnly,
                sameSite = siteOnly,
            )
            ?.let {
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
        siteOnly: Boolean = false,
        sameSite: Boolean = false,
    ): DiscoveryResult? {
        val ordered = LinkedHashSet(candidates)
        for (href in ordered) {
            if (siteOnly && isCommentsLink(href, "")) continue
            val feedBody = fetchBody(href) ?: continue
            val feed = tryParseFeed(href, feedBody)
            if (feed != null && siteOnly && isCommentsFeed(href, feed)) {
                logger.debug("Skipping comments feed {} from {}", href, baseUrl)
                continue
            }
            if (feed != null && sameSite && !sameHost(feed.link, baseUrl)) {
                logger.debug("Skipping {} from {}: its site is {}", href, baseUrl, feed.link)
                continue
            }
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

    /** A comments feed, by its address or its title: `/comments/feed/`, "Comments for …". */
    private fun isCommentsLink(href: String, title: String): Boolean =
        COMMENTS_PATH.containsMatchIn(href) || COMMENTS_TITLE.containsMatchIn(title)

    private fun isCommentsFeed(href: String, feed: SyndFeed): Boolean =
        isCommentsLink(href, feed.title.orEmpty())

    companion object {
        private val HEADERS =
            mapOf(
                "User-Agent" to "Mozilla/5.0 (compatible; Nevet/1.0; +https://bytecode.news)",
                "Accept" to
                    "application/rss+xml, application/atom+xml, application/xml, text/xml, text/html, */*;q=0.8",
            )

        /** Feeds with whole articles in them run to megabytes. */
        private const val MAX_FEED_BYTES = 10 * 1024 * 1024
        private val COMMENTS_PATH = Regex("(?i)/comments(/feed)?/?(\\?.*)?$|/comments/feed")
        private val COMMENTS_TITLE = Regex("(?i)^\\s*comments\\b|\\bcomments (feed|for|on)\\b")

        /** Whether [link] (a feed's own site address) is on [pageUrl]'s host, `www.` aside. */
        fun sameHost(link: String?, pageUrl: String): Boolean {
            val a = link?.let { runCatching { URI(it.trim()).host }.getOrNull() } ?: return false
            val b = runCatching { URI(pageUrl).host }.getOrNull() ?: return false
            return a.lowercase().removePrefix("www.") == b.lowercase().removePrefix("www.")
        }

        private val FEED_HINT_REGEX = Regex("(?i)(/|\\b)(feed|rss|atom)(\\.xml)?([/?#].*)?$")
        private val BODY_FEED_HINT_REGEX =
            Regex("(?i)\\b(rss|atom|feed|rss\\s+feed|atom\\s+feed)\\b")
    }
}
