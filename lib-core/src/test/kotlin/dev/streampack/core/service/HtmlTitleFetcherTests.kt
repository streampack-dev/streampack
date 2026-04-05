/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HtmlTitleFetcherTests {

    private fun fetcherWith(html: String?) =
        HtmlTitleFetcher(
            object : PageFetcher {
                override fun fetch(url: String): String? = html
            }
        )

    /** Map-based fetcher: returns the value whose key is a substring of the requested URL */
    private fun fetcherWith(responses: Map<String, String?>) =
        HtmlTitleFetcher(
            object : PageFetcher {
                override fun fetch(url: String): String? =
                    responses.entries.firstOrNull { url.contains(it.key) }?.value
            }
        )

    @Test
    fun `extracts title tag`() {
        val html = "<html><head><title>Hello World</title></head><body></body></html>"
        assertEquals("Hello World", fetcherWith(html).fetchTitle("http://example.com"))
    }

    @Test
    fun `falls back to og title when title tag is empty`() {
        val html =
            """<html><head><title></title>
            <meta property="og:title" content="OG Title Here">
            </head><body></body></html>"""
        assertEquals("OG Title Here", fetcherWith(html).fetchTitle("http://example.com"))
    }

    @Test
    fun `falls back to twitter title when title and og title are absent`() {
        val html =
            """<html><head>
            <meta name="twitter:title" content="Tweet Title">
            </head><body></body></html>"""
        assertEquals("Tweet Title", fetcherWith(html).fetchTitle("http://example.com"))
    }

    @Test
    fun `prefers og title over title tag`() {
        val html =
            """<html><head><title>Site Name</title>
            <meta property="og:title" content="Actual Article Title">
            </head><body></body></html>"""
        assertEquals("Actual Article Title", fetcherWith(html).fetchTitle("http://example.com"))
    }

    @Test
    fun `og title wins over useless title tag like YouTube`() {
        val html =
            """<html><head><title>- YouTube</title>
            <meta property="og:title" content="Top 5 Hollywood DISASTERS of 2025">
            </head><body></body></html>"""
        assertEquals(
            "Top 5 Hollywood DISASTERS of 2025",
            fetcherWith(html).fetchTitle("http://example.com"),
        )
    }

    @Test
    fun `prefers og title over twitter title`() {
        val html =
            """<html><head>
            <meta property="og:title" content="OG Title">
            <meta name="twitter:title" content="Tweet Title">
            </head><body></body></html>"""
        assertEquals("OG Title", fetcherWith(html).fetchTitle("http://example.com"))
    }

    @Test
    fun `returns null when no title found anywhere`() {
        val html = "<html><head></head><body>Just content</body></html>"
        assertNull(fetcherWith(html).fetchTitle("http://example.com"))
    }

    @Test
    fun `returns null when page fetch fails`() {
        assertNull(fetcherWith(null).fetchTitle("http://example.com"))
    }

    @Test
    fun `youtube oembed includes channel name`() {
        val oembedJson =
            """{"title": "Top 5 Hollywood DISASTERS of 2025", "author_name": "Nerdrotic"}"""
        val fetcher = fetcherWith(mapOf("youtube.com/oembed" to oembedJson))
        assertEquals(
            "YouTube: Top 5 Hollywood DISASTERS of 2025 | Nerdrotic",
            fetcher.fetchTitle("https://www.youtube.com/watch?v=abc"),
        )
    }

    @Test
    fun `youtube oembed falls back when author_name is missing`() {
        val oembedJson = """{"title": "Some Video Title"}"""
        val fetcher = fetcherWith(mapOf("youtube.com/oembed" to oembedJson))
        assertEquals(
            "YouTube: Some Video Title",
            fetcher.fetchTitle("https://www.youtube.com/watch?v=abc"),
        )
    }

    @Test
    fun `youtube oembed falls back when author_name is blank`() {
        val oembedJson = """{"title": "Some Video Title", "author_name": "  "}"""
        val fetcher = fetcherWith(mapOf("youtube.com/oembed" to oembedJson))
        assertEquals(
            "YouTube: Some Video Title",
            fetcher.fetchTitle("https://www.youtube.com/watch?v=abc"),
        )
    }

    @Test
    fun `fetchTitleResult exposes certificate warning metadata`() {
        val fetcher =
            HtmlTitleFetcher(
                object : PageFetcher {
                    override fun fetch(url: String): String? = null

                    override fun fetchResult(url: String): PageFetchResult =
                        PageFetchResult(
                            body = null,
                            finalUrl = url,
                            certificateInvalid = true,
                            warnings = listOf("TLS certificate validation failed"),
                        )
                }
            )

        val result = fetcher.fetchTitleResult("https://badcert.example.com")
        assertNull(result.title)
        assertTrue(result.certificateInvalid)
        assertFalse(result.warnings.isEmpty())
    }
}
