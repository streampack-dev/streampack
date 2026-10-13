/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class SuspectLinksTests {

    @ParameterizedTest
    @ValueSource(
        strings =
            [
                "http://localhost:3000/blog/ai",
                "https://LOCALHOST/",
                "http://app.localhost/x",
                "http://printer.local/",
                "http://wiki.corp.internal/page",
                "http://blog.test/post",
                "http://local_dev.localhost:3000/",
                "http://user@localhost:3000/",
                "http://127.0.0.1/",
                "http://127.9.9.9:8080/x",
                "http://0.0.0.0/",
                "http://10.1.2.3/",
                "http://172.16.0.1/",
                "http://192.168.1.10/",
                "http://169.254.169.254/latest/meta-data",
                "http://[::1]:3000/",
                "http://[fe80::1]/",
                "http://[fd12:3456::1]/",
                "ftp://example.com/file",
                "javascript:alert(1)",
                "/blog/relative",
                "not a url at all",
            ]
    )
    fun `development, private and non-web links look wrong`(url: String) {
        assertNotNull(SuspectLinks.check(url), url)
    }

    @ParameterizedTest
    @ValueSource(
        strings =
            [
                "https://www.cockroachlabs.com/blog/ai",
                "http://example.com/entry/1",
                "https://localization.example.com/",
                "https://local.news.example/",
                "https://testing.example.org/",
                "https://8.8.8.8/",
                "https://[2001:4860:4860::8888]/",
                "https://999.1.1.1/",
                "https://my_blog.example.com/post",
            ]
    )
    fun `public web links look fine`(url: String) {
        assertNull(SuspectLinks.check(url), url)
    }

    @Test
    fun `the reason says what's wrong`() {
        assertEquals("a local host", SuspectLinks.check("http://localhost:3000/"))
        assertEquals("a loopback address", SuspectLinks.check("http://127.0.0.1/"))
        assertEquals("a private address", SuspectLinks.check("http://192.168.0.2/"))
        assertEquals("not http or https", SuspectLinks.check("ftp://example.com/"))
    }

    @Test
    fun `a feed with wrong links gets one line with the count and an example`() {
        val warning =
            SuspectLinks.warning(
                "Blog posts | RSS Feed",
                "https://www.cockroachlabs.com/rss.xml",
                "http://localhost:3000",
                listOf("http://localhost:3000/blog/ai", "http://localhost:3000/blog/b"),
            )

        assertEquals(
            "Feed \"Blog posts | RSS Feed\" (https://www.cockroachlabs.com/rss.xml): " +
                "2 of 2 item links look wrong, e.g. http://localhost:3000/blog/ai (a local host); " +
                "its site link looks wrong: http://localhost:3000 (a local host)",
            warning,
        )
    }

    @Test
    fun `only the wrong links are counted, and the site alone is said`() {
        assertEquals(
            "Feed \"F\" (https://f.example/rss): 1 of 2 item links look wrong, " +
                "e.g. http://10.0.0.5/x (a private address)",
            SuspectLinks.warning(
                "F",
                "https://f.example/rss",
                "https://f.example/",
                listOf("https://f.example/a", "http://10.0.0.5/x"),
            ),
        )
        assertEquals(
            "Feed \"F\" (https://f.example/rss): its site link looks wrong: " +
                "http://localhost:3000 (a local host)",
            SuspectLinks.warning("F", "https://f.example/rss", "http://localhost:3000", listOf()),
        )
    }

    @Test
    fun `a feed whose links look fine gets no warning`() {
        assertNull(
            SuspectLinks.warning(
                "F",
                "https://f.example/rss",
                "https://f.example/",
                listOf("https://f.example/a"),
            )
        )
        assertNull(SuspectLinks.warning("F", "https://f.example/rss", null, listOf()))
    }
}
