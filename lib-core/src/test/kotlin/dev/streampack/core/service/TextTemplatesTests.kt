/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TextTemplatesTests {
    @Test
    fun `placeholders are filled from the context`() {
        assertEquals(
            "Your code for Gholem is 123456.",
            TextTemplates.fill(
                "Your code for {{siteName}} is {{code}}.",
                mapOf("siteName" to "Gholem", "code" to 123456),
            ),
        )
    }

    @Test
    fun `a placeholder with no value stays visible, and spaces inside braces are allowed`() {
        assertEquals(
            "Hello {{nobody}}, from Gholem",
            TextTemplates.fill(
                "Hello {{nobody}}, from {{ siteName }}",
                mapOf("siteName" to "Gholem"),
            ),
        )
    }

    @Test
    fun `text without placeholders is unchanged, braces and all`() {
        val prompt = "Return JSON like {\"a\": {\"b\": 1}} and nothing else."
        assertEquals(prompt, TextTemplates.fill(prompt, mapOf("a" to "x")))
    }

    @Test
    fun `a value with braces or dollars is put in as it is`() {
        assertEquals(
            "x {{code}} \$1",
            TextTemplates.fill("x {{v}}", mapOf("v" to "{{code}} \$1", "code" to "no")),
        )
    }

    @Test
    fun `the bundled template is read and filled when nothing overrides it`() {
        val text =
            ClasspathTextTemplates()
                .render(
                    "mail-sign-in-code",
                    "dev/streampack/core/mail/mail-sign-in-code.txt",
                    mapOf("siteName" to "Gholem", "code" to "654321", "minutes" to 10),
                )
        val mail = MailText.parse(text)
        assertEquals("Your sign-in code for Gholem", mail.subject)
        assertEquals(true, mail.body.contains("654321"), mail.body)
        assertEquals(true, mail.body.contains("10 minutes"), mail.body)
    }

    @Test
    fun `a mail template's first line is its subject, and the rest its body`() {
        val mail = MailText.parse("Subject: Hello there\n\nLine one.\nLine two.\n")
        assertEquals("Hello there", mail.subject)
        assertEquals("Line one.\nLine two.", mail.body)
    }

    @Test
    fun `a mail template without a subject line keeps the fallback subject`() {
        val mail = MailText.parse("Just a body.", fallbackSubject = "Notice")
        assertEquals("Notice", mail.subject)
        assertEquals("Just a body.", mail.body)
    }
}
