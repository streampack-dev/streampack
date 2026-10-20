/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.service

import org.assertj.core.api.Assertions.assertThat
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * A post's headings carry ids (#270), from flexmark, prefixed with `section-` so none can take the
 * name of something on the page; a comment's don't, so they can't clash with the post's.
 */
@SpringBootTest
class HeadingIdsTests {
    @Autowired lateinit var markdownRenderingService: MarkdownRenderingService

    private fun ids(html: String): List<Pair<String, String>> =
        Jsoup.parseBodyFragment(html).select("h1, h2, h3, h4, h5, h6").map {
            it.tagName() to it.id()
        }

    @Test
    fun `a post's headings get ids from their words, prefixed`() {
        val html =
            markdownRenderingService.renderPost(
                "## Why it matters\n\nText.\n\n### The fix, in short\n",
            )
        assertThat(ids(html))
            .containsExactly("h2" to "section-why-it-matters", "h3" to "section-the-fix-in-short")
        assertThat(html).contains("<h2 id=\"section-why-it-matters\">Why it matters</h2>")
    }

    @Test
    fun `repeated headings are told apart within a post`() {
        val html = markdownRenderingService.renderPost("## Notes\n\nOne.\n\n## Notes\n\nTwo.\n")
        val shown = ids(html).map { it.second }
        assertThat(shown).containsExactly("section-notes", "section-notes-1")
    }

    @Test
    fun `a comment's headings get no ids`() {
        val html = markdownRenderingService.render("## Why it matters\n\nText.")
        assertThat(ids(html)).containsExactly("h2" to "")
    }

    @Test
    fun `a post's other ids are left as flexmark writes them, and the HTML is still sanitized`() {
        val html =
            markdownRenderingService.renderPost(
                "## Title\n\nA claim.[^1] [bad](javascript:alert(1))\n\n[^1]: A source.\n",
            )
        val doc = Jsoup.parseBodyFragment(html)
        assertThat(doc.select("[id^=fn]")).isNotEmpty
        assertThat(doc.select("[id^=section-]").map { it.tagName() }).containsOnly("h2")
        assertThat(html).doesNotContain("javascript:")
    }
}
