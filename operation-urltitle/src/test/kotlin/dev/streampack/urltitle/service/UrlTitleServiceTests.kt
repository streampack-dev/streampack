/* Joseph B. Ottinger (C)2026 */
package dev.streampack.urltitle.service

import java.util.stream.Stream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class UrlTitleServiceTests {
    @Autowired lateinit var service: UrlTitleService

    @ParameterizedTest
    @MethodSource("urlSimilarityInputs")
    fun `jaccard similarity meets expected thresholds`(
        url: String,
        title: String,
        minimumSimilarity: Double,
    ) {
        assertTrue(service.calculateJaccardSimilarity(url, title) >= minimumSimilarity)
    }

    @Test
    fun `extract URLs from mixed text`() {
        val text = "check out https://example.com and also http://foo.bar/baz"
        val urls = service.extractUrls(text)
        assertEquals(2, urls.size)
        assertEquals("https://example.com", urls[0])
        assertEquals("http://foo.bar/baz", urls[1])
    }

    @Test
    fun `trailing punctuation stripped from URLs`() {
        val text = "see https://example.com/page."
        val urls = service.extractUrls(text)
        assertEquals(1, urls.size)
        assertEquals("https://example.com/page", urls[0])
    }

    @Test
    fun `trailing comma stripped from URLs`() {
        val text = "visit https://example.com/a, https://example.com/b, and more"
        val urls = service.extractUrls(text)
        assertEquals(2, urls.size)
        assertEquals("https://example.com/a", urls[0])
        assertEquals("https://example.com/b", urls[1])
    }

    @Test
    fun `trailing parenthesis stripped from URLs`() {
        val text = "(https://example.com/page)"
        val urls = service.extractUrls(text)
        assertEquals(1, urls.size)
        assertEquals("https://example.com/page", urls[0])
    }

    @Test
    fun `no URLs returns empty list`() {
        val urls = service.extractUrls("no urls here at all")
        assertTrue(urls.isEmpty())
    }

    @Test
    fun `pastebin is ignored by default`() {
        assertTrue(service.isIgnoredHost("https://pastebin.com/abc123"))
    }

    @Test
    fun `subdomain of ignored host is not ignored`() {
        assertFalse(service.isIgnoredHost("https://foo.pastebin.com/something"))
    }

    @Test
    fun `non-ignored host passes through`() {
        assertFalse(service.isIgnoredHost("https://bytecode.news/"))
    }

    @Test
    fun `add and delete ignored host`() {
        assertFalse(service.isIgnoredHost("https://test-host.example.com/page"))
        service.addIgnoredHost("test-host.example.com")
        assertTrue(service.isIgnoredHost("https://test-host.example.com/page"))
        service.deleteIgnoredHost("test-host.example.com")
        assertFalse(service.isIgnoredHost("https://test-host.example.com/page"))
    }

    // -- www normalization --

    @Test
    fun `www prefix is normalized when adding ignored host`() {
        service.addIgnoredHost("www.example.org")
        assertTrue(service.isIgnoredHost("https://example.org/page"))
        assertTrue(service.isIgnoredHost("https://www.example.org/page"))
    }

    @Test
    fun `ignoring bare domain also blocks www variant`() {
        service.addIgnoredHost("normalize-test.example.com")
        assertTrue(service.isIgnoredHost("https://www.normalize-test.example.com/page"))
    }

    @Test
    fun `delete with www prefix removes normalized entry`() {
        service.addIgnoredHost("delete-test.example.com")
        service.deleteIgnoredHost("www.delete-test.example.com")
        assertFalse(service.isIgnoredHost("https://delete-test.example.com/page"))
    }

    @Test
    fun `filter URLs removes ignored hosts`() {
        val urls = listOf("https://pastebin.com/abc", "https://bpa.st/bar", "https://bytecode.news")
        val filtered = urls.filter { !service.isIgnoredHost(it) }
        assertEquals(1, filtered.size)
        assertEquals("https://bytecode.news", filtered[0])
    }

    companion object {
        @JvmStatic
        fun urlSimilarityInputs(): Stream<Arguments> =
            Stream.of(
                Arguments.of(
                    "http://foo.com/bar/baz.html",
                    "never gonna give you up, never gonna let you down",
                    0.0,
                ),
                Arguments.of("http://foo.com/bar/baz.html", "bar baz (foo.com)", 0.9),
                Arguments.of("http://foo.com/bar/baz.html", "bar baz", 0.4),
                Arguments.of(
                    "https://bytecode.news/2023/09/06/weechat-on-osx/",
                    "Weechat on OSX",
                    0.370,
                ),
                Arguments.of(
                    "https://bytecode.news/2023/09/06/weechat/",
                    "Weechat (bytecode news)",
                    0.49,
                ),
                Arguments.of(
                    "https://www.cnn.com/2024/08/19/business/harley-davidson-dei-john-deere-tractor-supply/index.html",
                    "Harley-Davidson is dropping diversity initiatives after right-wing anti-DEI campaign | CNN Business",
                    0.26,
                ),
            )
    }
}
