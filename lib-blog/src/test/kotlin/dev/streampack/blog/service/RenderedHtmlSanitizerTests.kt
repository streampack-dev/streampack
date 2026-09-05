/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RenderedHtmlSanitizerTests {
    private val sanitizer = RenderedHtmlSanitizer()

    @Test
    fun `removes javascript hrefs but keeps the link text`() {
        val result = sanitizer.sanitize("<p><a href=\"javascript:alert(1)\">click</a></p>")
        assertFalse(result.contains("javascript:"), result)
        assertTrue(result.contains("click"), result)
    }

    @Test
    fun `removes script tags and event handler attributes`() {
        val result =
            sanitizer.sanitize(
                "<p onclick=\"steal()\">hi</p><script>alert(1)</script><img src=\"/x.png\" onerror=\"steal()\">"
            )
        assertFalse(result.contains("script"), result)
        assertFalse(result.contains("onclick"), result)
        assertFalse(result.contains("onerror"), result)
        assertTrue(result.contains("<p>hi</p>"), result)
        assertTrue(result.contains("src=\"/x.png\""), result)
    }

    @Test
    fun `removes iframes objects and style attributes`() {
        val result =
            sanitizer.sanitize(
                "<iframe src=\"https://evil.example\"></iframe><p style=\"position:fixed\">x</p><object data=\"a\"></object>"
            )
        assertFalse(result.contains("iframe"), result)
        assertFalse(result.contains("style="), result)
        assertFalse(result.contains("object"), result)
        assertTrue(result.contains("<p>x</p>"), result)
    }

    @Test
    fun `keeps class and id attributes used by flexmark extensions`() {
        val html =
            "<div class=\"adm-block adm-note\"><div class=\"adm-heading\">R</div></div>" +
                "<sup id=\"fnref-1\"><a class=\"footnote-ref\" href=\"#fn-1\">1</a></sup>"
        val result = sanitizer.sanitize(html)
        assertTrue(result.contains("class=\"adm-block adm-note\""), result)
        assertTrue(result.contains("id=\"fnref-1\""), result)
        assertTrue(result.contains("href=\"#fn-1\""), result)
    }

    @Test
    fun `keeps task list checkboxes`() {
        val html =
            "<ul><li class=\"task-list-item\"><input type=\"checkbox\" class=\"task-list-item-checkbox\" checked=\"checked\" disabled=\"disabled\" readonly=\"readonly\" />done</li></ul>"
        val result = sanitizer.sanitize(html)
        assertTrue(result.contains("type=\"checkbox\""), result)
        assertTrue(result.contains("checked"), result)
    }

    @Test
    fun `does not pretty print or otherwise reformat clean html`() {
        assertEquals("<h1>Published</h1>", sanitizer.sanitize("<h1>Published</h1>"))
        assertEquals("<p>One</p>\n<p>Two</p>", sanitizer.sanitize("<p>One</p>\n<p>Two</p>"))
    }

    @Test
    fun `is idempotent`() {
        val once = sanitizer.sanitize("<p><a href=\"javascript:x\">a</a> <b>b</b></p>")
        assertEquals(once, sanitizer.sanitize(once))
    }

    @Test
    fun `blank input stays blank`() {
        assertEquals("", sanitizer.sanitize(""))
        assertEquals("", sanitizer.sanitize("   "))
    }
}
