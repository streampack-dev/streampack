/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.outgoing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Which of a post's links lead off the site (#112, #128). */
class OutgoingLinksTests {
    @Test
    fun `absolute web links to other hosts, once each, without fragments`() {
        val html =
            """
            <a href="https://other.example/post#top">a</a>
            <a href="https://other.example/post">again</a>
            <a href="/posts/ours">relative</a>
            <a href="https://bytecode.news/posts/ours">ours</a>
            <a href="https://www.bytecode.news/x">ours, www</a>
            <a href="https://pudl.bytecode.news/x">ours, a subdomain</a>
            <a href="mailto:someone@example.com">mail</a>
            <a href="javascript:alert(1)">script</a>
            <a href="http://plain.example/q?a=1&amp;b=2">query</a>
            """

        assertEquals(
            listOf("https://other.example/post", "http://plain.example/q?a=1&b=2"),
            OutgoingLinks.extract(html, "bytecode.news"),
        )
    }

    @Test
    fun `a host is lower case, without www`() {
        assertEquals("example.com", OutgoingLinks.host("https://WWW.Example.com/x"))
    }
}
