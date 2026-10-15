/* Joseph B. Ottinger (C)2026 */
package dev.streampack.config

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * docs/reference/templates.md gives every bundled template's text, to copy into generative/ and
 * edit (#232). This keeps it the same as the files the server uses.
 */
class TemplatesReferenceTests {
    private val root = Path.of("..")
    private val reference = Files.readString(root.resolve("docs/reference/templates.md"))

    private val bundled =
        listOf(
            "lib-core/src/main/resources/dev/streampack/core/mail/mail-sign-in-code.txt",
            "lib-core/src/main/resources/dev/streampack/core/mail/mail-verify-email.txt",
            "lib-blog/src/main/resources/dev/streampack/blog/mail/mail-new-draft.txt",
            "operation-ideas/src/main/resources/dev/streampack/ideas/prompts/suggest-prompt.txt",
            "service-rss/src/main/resources/dev/streampack/rss/prompts/rss-rating-rubric.txt",
        )

    @Test
    fun `the reference gives each bundled template's text exactly`() {
        for (path in bundled) {
            val text = Files.readString(root.resolve(path)).trimEnd('\n')
            assertTrue(
                reference.contains("````text\n$text\n````"),
                "$path differs from the reference",
            )
            assertTrue(
                reference.contains("Bundled at `$path`."),
                "$path isn't named in the reference",
            )
        }
    }

    @Test
    fun `every template in the code is in the reference`() {
        val names = Regex("""render\(\s*"([a-z0-9-]+)"""")
        val used =
            Files.walk(root)
                .filter { it.toString().endsWith(".kt") && "/src/main/" in it.toString() }
                .map { Files.readString(it) }
                .toList()
                .flatMap { source -> names.findAll(source).map { it.groupValues[1] }.toList() }
                .toSet()
        for (name in used + setOf("mail-sign-in-code", "mail-verify-email")) {
            assertTrue(reference.contains("### `$name`"), "$name has no section in the reference")
        }
    }
}
