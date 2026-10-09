/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.fetch

import dev.streampack.core.extensions.compress
import org.jsoup.Jsoup

/**
 * A page's readable text, by simple heuristics: scripts and styles dropped, then the longest of its
 * `<article>`, its `<main>` and its whole body, whitespace collapsed. Used by `suggest <url>` and
 * by the RSS rating guess (#187), when a feed item carries no content of its own.
 */
object ArticleText {
    /** A page's title and readable text. */
    data class Extracted(val title: String, val text: String)

    /**
     * [html]'s title (Open Graph, Twitter, then `<title>`, else [baseUrl]) and readable text; null
     * when there's no text.
     */
    fun extract(html: String, baseUrl: String): Extracted? {
        val document = Jsoup.parse(html, baseUrl)
        document.select("script, style, noscript").remove()
        val title =
            document.select("meta[property=og:title]").attr("content").takeIf { it.isNotBlank() }
                ?: document.select("meta[name=twitter:title]").attr("content").takeIf {
                    it.isNotBlank()
                }
                ?: document.title().takeIf { it.isNotBlank() }
                ?: baseUrl
        val article = document.selectFirst("article")?.text().orEmpty().compress()
        val main = document.selectFirst("main")?.text().orEmpty().compress()
        val body = document.body().text().compress()
        val text = sequenceOf(article, main, body).maxByOrNull { it.length }.orEmpty()
        if (text.isBlank()) return null
        return Extracted(title.trim(), text)
    }
}
