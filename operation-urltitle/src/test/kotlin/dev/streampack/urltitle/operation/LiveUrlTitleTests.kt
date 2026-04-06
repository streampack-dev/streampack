/* Joseph B. Ottinger (C)2026 */
package dev.streampack.urltitle.operation

import dev.streampack.core.service.HtmlTitleFetcher
import dev.streampack.core.service.HttpPageFetcher
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.slf4j.LoggerFactory

/**
 * Live integration tests that hit real URLs. Disabled by default. Run with:
 * ```
 * ./mvnw test -pl operation-urltitle -Dtest=LiveUrlTitleTests -Dlive.tests=true
 * ```
 */
@EnabledIfSystemProperty(named = "live.tests", matches = "true")
class LiveUrlTitleTests {
    private val logger = LoggerFactory.getLogger(LiveUrlTitleTests::class.java)
    private val pageFetcher = HttpPageFetcher()
    private val titleFetcher = HtmlTitleFetcher(pageFetcher)

    @Test
    fun `fetch title from x_com - diagnostic only`() {
        val url = "https://x.com/dev_maims/status/2022588131623501927"
        logger.info("Fetching page body for {}", url)
        val body = pageFetcher.fetch(url)
        logger.info("Body returned: {} chars", body?.length ?: "null")
        if (body != null) {
            logger.info("First 500 chars: {}", body.take(500))
            logMetaTags(body, url)
        }

        logger.info("Fetching title for {}", url)
        val title = titleFetcher.fetchTitle(url)
        logger.info("Title result: {}", title ?: "null")
        // No assertions - x.com requires API access for reliable title extraction
    }

    @Test
    fun `fetch title from youtube`() {
        val url = "https://www.youtube.com/watch?v=jNDWnMfDnuw"
        logger.info("Fetching page body for {}", url)
        val body = pageFetcher.fetch(url)
        logger.info("Body returned: {} chars", body?.length ?: "null")
        if (body != null) {
            logger.info("First 500 chars: {}", body.take(500))
            logMetaTags(body, url)
        }

        logger.info("Fetching title for {}", url)
        val title = titleFetcher.fetchTitle(url)
        logger.info("Title result: {}", title ?: "null")
        assertNotNull(title, "Expected a title from YouTube but got null")
        assertTrue(title!!.isNotBlank(), "Title from YouTube should not be blank")
        assertTrue(title.contains("DISASTERS"))
    }

    /** Dumps all meta tags from the HTML for diagnostic purposes */
    private fun logMetaTags(body: String, url: String) {
        val doc = org.jsoup.Jsoup.parse(body)
        logger.info("--- <title> for {} ---", url)
        logger.info("  {}", doc.title().ifBlank { "(empty)" })
        logger.info("--- meta tags for {} ---", url)
        for (meta in doc.select("meta[property], meta[name]")) {
            val key = meta.attr("property").ifBlank { meta.attr("name") }
            val content = meta.attr("content")
            if (content.isNotBlank()) {
                logger.info("  {} = {}", key, content)
            }
        }
        logger.info("--- end meta tags ---")
    }
}
