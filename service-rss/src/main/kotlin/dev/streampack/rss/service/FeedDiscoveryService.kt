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

/** Fetches a URL and discovers the RSS/Atom feed, either directly or via HTML link discovery */
@Service
class FeedDiscoveryService(private val properties: RssProperties) {

    private val logger = LoggerFactory.getLogger(FeedDiscoveryService::class.java)

    /** Fetches a known feed URL and parses it directly, without HTML discovery fallback */
    fun fetchFeed(feedUrl: String): SyndFeed? {
        val body = fetchBody(feedUrl) ?: return null
        return tryParseFeed(feedUrl, body)
    }

    /**
     * Discover a feed from the given URL. Tries direct parsing first, then falls back to HTML
     * alternate-link discovery.
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

    /** Parse HTML for alternate feed links and try each one until a valid feed is found */
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
                .map { it.absUrl("href") }
                .filter { it.isNotBlank() && FEED_HINT_REGEX.containsMatchIn(it) }
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

    companion object {
        private val FEED_HINT_REGEX = Regex("(?i)(/|\\b)(feed|rss|atom)(\\.xml)?([/?#].*)?$")
    }
}
