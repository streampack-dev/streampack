/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.outgoing

import dev.streampack.core.fetch.FetchProperties
import dev.streampack.core.fetch.FetchResponse
import dev.streampack.core.fetch.GuardedFetcher
import java.net.URI
import java.net.http.HttpHeaders
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Finding where to send a mention (#112), as the Webmention and Pingback specs have it. */
class MentionSenderTests {
    private val sender = MentionSender(GuardedFetcher(FetchProperties()), OutgoingLinksProperties())

    private fun page(
        body: String,
        vararg headers: Pair<String, String>,
        at: String = "https://site.example/a/post",
    ) =
        FetchResponse(
            status = 200,
            finalUri = URI(at),
            headers = HttpHeaders.of(headers.groupBy({ it.first }, { it.second })) { _, _ -> true },
            body = body,
        )

    @Test
    fun `a Link header names the endpoint, relative to the page`() {
        val found =
            page("", "Link" to "</webmention>; rel=\"webmention\"", "Content-Type" to "text/html")

        assertEquals("https://site.example/webmention", sender.webmentionEndpoint(found))
    }

    @Test
    fun `a Link header among others, with several rels`() {
        val found =
            page(
                "",
                "Link" to
                    "<https://cdn.example/x.css>; rel=preload, <https://wm.example/e>; rel=\"other webmention\"",
            )

        assertEquals("https://wm.example/e", sender.webmentionEndpoint(found))
    }

    @Test
    fun `a link or an a element names it otherwise, the first in the document`() {
        assertEquals(
            "https://site.example/a/wm",
            sender.webmentionEndpoint(
                page(
                    """<html><body><a rel="webmention" href="wm">x</a><link rel="webmention" href="/later"></body></html>"""
                )
            ),
        )
        assertEquals(
            "https://site.example/e",
            sender.webmentionEndpoint(
                page("""<html><head><link rel="nofollow webmention" href="/e"></head></html>""")
            ),
        )
    }

    @Test
    fun `an empty href is the page itself`() {
        assertEquals(
            "https://site.example/a/post",
            sender.webmentionEndpoint(
                page("""<html><head><link rel="webmention" href=""></head></html>""")
            ),
        )
    }

    @Test
    fun `the header comes before the document`() {
        val found =
            page(
                """<html><head><link rel="webmention" href="/doc"></head></html>""",
                "Link" to "</header>; rel=webmention",
            )

        assertEquals("https://site.example/header", sender.webmentionEndpoint(found))
    }

    @Test
    fun `a pingback server is named by header or link`() {
        assertEquals(
            "https://site.example/xmlrpc.php",
            sender.pingbackServer(page("", "X-Pingback" to "https://site.example/xmlrpc.php")),
        )
        assertEquals(
            "https://site.example/xmlrpc.php",
            sender.pingbackServer(
                page("""<html><head><link rel="pingback" href="/xmlrpc.php"></head></html>""")
            ),
        )
    }

    @Test
    fun `a page that names neither has no endpoint`() {
        val plain = page("<html><body>hello</body></html>")

        assertNull(sender.webmentionEndpoint(plain))
        assertNull(sender.pingbackServer(plain))
    }

    @Test
    fun `a page that isn't HTML isn't read for links`() {
        val pdf = page("""<link rel="webmention" href="/e">""", "Content-Type" to "application/pdf")

        assertNull(sender.webmentionEndpoint(pdf))
    }
}
