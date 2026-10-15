/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import org.springframework.core.io.DefaultResourceLoader

/**
 * Text an operator can replace without a rebuild, such as the mail the server sends (#232): a named
 * template, with a bundled default on the classpath. `lib-generative` implements this so a file in
 * `generative/` (`STREAMPACK_GENERATIVE_PROMPT_DIR`) wins over the default; without it,
 * [ClasspathTextTemplates] reads the default.
 *
 * Templates may use `{{key}}` placeholders, filled from the context by [fill].
 */
interface TextTemplates {
    /** The template [name], or the bundled [fallbackResource], with [context] filled in. */
    fun render(
        name: String,
        fallbackResource: String,
        context: Map<String, Any?> = emptyMap(),
    ): String

    companion object {
        private val PLACEHOLDER = Regex("""\{\{\s*([A-Za-z][A-Za-z0-9_]*)\s*}}""")

        /**
         * [text] with each `{{key}}` replaced by its value in [context]. A key with no value stays
         * as written, so a mistyped placeholder shows rather than vanishing. Values go in as they
         * are: a value holding `{{x}}` isn't filled again.
         */
        fun fill(text: String, context: Map<String, Any?>): String =
            PLACEHOLDER.replace(text) { match ->
                val key = match.groupValues[1]
                if (context.containsKey(key)) context[key]?.toString().orEmpty() else match.value
            }
    }
}

/** [TextTemplates] from the bundled defaults alone. */
class ClasspathTextTemplates : TextTemplates {
    private val loader = DefaultResourceLoader()

    override fun render(
        name: String,
        fallbackResource: String,
        context: Map<String, Any?>,
    ): String =
        TextTemplates.fill(
            loader.getResource("classpath:$fallbackResource").inputStream.bufferedReader().use {
                it.readText()
            },
            context,
        )
}

/** A mail template: its first line, `Subject: …`, and the body after it. */
data class MailText(val subject: String, val body: String) {
    companion object {
        private val SUBJECT = Regex("""^Subject:\s*(.*)$""", RegexOption.IGNORE_CASE)

        /**
         * [text] as subject and body; without a `Subject:` line, [fallbackSubject] and all of it.
         */
        fun parse(text: String, fallbackSubject: String = ""): MailText {
            val lines = text.trim().lines()
            val subject = lines.firstOrNull()?.let { SUBJECT.find(it.trim()) }?.groupValues?.get(1)
            return if (subject == null) MailText(fallbackSubject, text.trim())
            else MailText(subject.trim(), lines.drop(1).joinToString("\n").trim())
        }
    }
}
