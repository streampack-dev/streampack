/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.service

import com.vladsch.flexmark.ext.admonition.AdmonitionExtension
import com.vladsch.flexmark.ext.aside.AsideExtension
import com.vladsch.flexmark.ext.autolink.AutolinkExtension
import com.vladsch.flexmark.ext.footnotes.FootnoteExtension
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughSubscriptExtension
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension
import com.vladsch.flexmark.ext.tables.TablesExtension
import com.vladsch.flexmark.ext.wikilink.WikiLinkExtension
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.data.MutableDataSet
import java.net.URI
import org.springframework.stereotype.Service

/** Converts markdown source to sanitized HTML and generates plain-text excerpts */
@Service
class MarkdownRenderingService(
    private val excerptSummarizerService: ExcerptSummarizerService,
    private val factoidWikiLinkResolver: FactoidWikiLinkResolver? = null,
) {
    private val factoidAnchorPattern =
        Regex(
            """<a\b([^>]*?)href=(['"])/factoids/([^'"#?]+)\2([^>]*)>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

    private val parser: Parser
    private val renderer: HtmlRenderer

    init {
        val options = MutableDataSet()
        options.set(
            Parser.EXTENSIONS,
            listOf(
                TablesExtension.create(),
                AutolinkExtension.create(),
                StrikethroughSubscriptExtension.create(),
                TaskListExtension.create(),
                WikiLinkExtension.create(),
                FootnoteExtension.create(),
                AsideExtension.create(),
                AdmonitionExtension.create(),
            ),
        )
        // Route wiki links to factoid detail pages.
        options.set(WikiLinkExtension.LINK_FIRST_SYNTAX, false)
        options.set(WikiLinkExtension.LINK_PREFIX, "/factoids/")
        options.set(WikiLinkExtension.LINK_FILE_EXTENSION, "")
        // Keep selectors stable: do not rewrite spaces to '-' in wiki targets.
        options.set(WikiLinkExtension.LINK_ESCAPE_CHARS, "+/<>")
        options.set(WikiLinkExtension.LINK_REPLACE_CHARS, "----")
        // Strip raw HTML tags from markdown input
        options.set(HtmlRenderer.ESCAPE_HTML, true)
        parser = Parser.builder(options).build()
        renderer = HtmlRenderer.builder(options).build()
    }

    /** Render markdown source to HTML with raw HTML escaped */
    fun render(markdownSource: String): String {
        if (markdownSource.isBlank()) return ""
        val document = parser.parse(markdownSource)
        val html = renderer.render(document).trim()
        return resolveFactoidWikiLinks(html)
    }

    /** Generate a plain-text excerpt by stripping markup and truncating at word boundary */
    fun excerpt(markdownSource: String, maxLength: Int = 400, maxSentences: Int = 3): String {
        if (markdownSource.isBlank()) return ""
        val plainText = stripMarkup(markdownSource)
        if (plainText.isBlank()) return ""
        val summarized =
            excerptSummarizerService
                .summarize(plainText, maxSentences)
                .replace(Regex("\\s+"), " ")
                .trim()
                .ifBlank { plainText }
        if (summarized.length <= maxLength) return summarized
        // Truncate at last space before maxLength to avoid cutting words
        val truncated = summarized.substring(0, maxLength)
        val lastSpace = truncated.lastIndexOf(' ')
        return if (lastSpace > 0) truncated.substring(0, lastSpace) + "..." else truncated + "..."
    }

    /** Remove markdown syntax and HTML tags to produce plain text */
    private fun stripMarkup(markdownSource: String): String {
        // Render to HTML first, then strip all tags
        val document = parser.parse(markdownSource)
        val html = renderer.render(document)
        return html
            .replace(Regex("<[^>]+>"), "") // strip HTML tags
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("\\s+"), " ") // collapse whitespace
            .replace(Regex("\\s+([,.;:!?])"), "$1") // no space before punctuation
            .replace(Regex("(\\(|\\[|\\{)\\s+"), "$1") // no leading space inside brackets
            .replace(Regex("\\s+(\\)|\\]|\\})"), "$1") // no trailing space before bracket close
            .replace(Regex("(\\d)\\s*-\\s*([A-Za-z])"), "$1-$2") // 4- player -> 4-player
            // Recover common Java generic patterns flattened by upstream text extraction.
            .replace(
                Regex(
                    "\\b([A-Za-z_][A-Za-z0-9_]*)\\s+([A-Z])\\s+extends\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+\\2\\b"
                ),
                "$1<$2> extends $3<$2>",
            )
            .replace(
                Regex("\\bextend\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+([A-Z][A-Za-z0-9_]*)\\b"),
                "extend $1<$2>",
            )
            .trim()
    }

    private fun resolveFactoidWikiLinks(html: String): String {
        if (factoidWikiLinkResolver == null || !html.contains("/factoids/")) return html
        return factoidAnchorPattern.replace(html) { match ->
            val beforeAttrs = match.groupValues[1]
            val selectorRaw = match.groupValues[3]
            val afterAttrs = match.groupValues[4]
            val innerHtml = match.groupValues[5]
            val selector = decodeSelector(selectorRaw)
            if (selector.isBlank()) return@replace match.value

            val metadata = factoidWikiLinkResolver.resolve(selector) ?: return@replace match.value
            val href = normalizeHref(metadata.href) ?: return@replace match.value
            val title = normalizeFactoidTitle(metadata.title)
            buildAnchor(beforeAttrs, afterAttrs, innerHtml, href, title)
        }
    }

    private fun decodeSelector(selectorRaw: String): String =
        try {
            java.net.URLDecoder.decode(selectorRaw, java.nio.charset.StandardCharsets.UTF_8).trim()
        } catch (_: Exception) {
            selectorRaw.trim()
        }

    private fun normalizeHref(candidate: String?): String? {
        val raw = candidate?.trim().orEmpty()
        if (raw.isBlank()) return null
        return try {
            val uri = URI(raw)
            val scheme = uri.scheme?.lowercase().orEmpty()
            if (scheme != "http" && scheme != "https") null else uri.toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun htmlEscape(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    private fun normalizeFactoidTitle(candidate: String?): String {
        val trimmed = candidate?.trim().orEmpty()
        if (trimmed.isBlank()) return ""
        return if (trimmed.startsWith("<reply>", ignoreCase = true)) {
            trimmed.substring("<reply>".length).trimStart()
        } else {
            trimmed
        }
    }

    private fun buildAnchor(
        beforeAttrs: String,
        afterAttrs: String,
        innerHtml: String,
        href: String,
        title: String,
    ): String {
        val mergedAttrs =
            "$beforeAttrs$afterAttrs"
                .replace(Regex("""\s+href=(['"]).*?\1""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s+title=(['"]).*?\1""", RegexOption.IGNORE_CASE), "")
                .trim()
        val attrs = if (mergedAttrs.isBlank()) "" else " $mergedAttrs"
        val titleAttr = if (title.isBlank()) "" else " title=\"${htmlEscape(title)}\""
        return "<a href=\"${htmlEscape(href)}\"$titleAttr$attrs>$innerHtml</a>"
    }
}
