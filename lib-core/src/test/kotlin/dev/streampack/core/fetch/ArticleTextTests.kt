/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.fetch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** A page's readable text: the longest of its article, main and body, scripts dropped. */
class ArticleTextTests {
    @Test
    fun `the article's text, with the Open Graph title`() {
        val html =
            """
            <html><head><title>Plain</title><meta property="og:title" content="Rich title"></head>
            <body><nav>Home About</nav><article><h1>Heading</h1><p>The   story
            itself.</p><script>track()</script></article></body></html>
            """
        val extracted = ArticleText.extract(html, "https://a.example/1")!!
        assertEquals("Rich title", extracted.title)
        assertEquals("Home About Heading The story itself.", extracted.text)
    }

    @Test
    fun `the title falls back to the page's, then the address`() {
        assertEquals(
            "Plain",
            ArticleText.extract("<title>Plain</title><p>Text</p>", "https://a.example")!!.title,
        )
        assertEquals(
            "https://a.example",
            ArticleText.extract("<p>Text</p>", "https://a.example")!!.title,
        )
    }

    @Test
    fun `a page with no text has none`() {
        assertNull(ArticleText.extract("<html><body><script>x()</script></body></html>", "u"))
    }
}
