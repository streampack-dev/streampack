/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.service

import com.rometools.rome.feed.synd.SyndEntry
import org.jsoup.Jsoup

/**
 * An entry's summary as plain text (#98): its RSS description, or its Atom summary, else its
 * content, with any HTML reduced to its text, whitespace collapsed, and cut at a word to fit the
 * column. Nothing, or nothing more than the title, is no summary. Plain text only, so no client
 * ever renders a feed's HTML.
 */
object EntrySummary {
    const val MAX_LENGTH = 500

    fun of(entry: SyndEntry): String? {
        val raw =
            entry.description?.value?.takeIf { it.isNotBlank() }
                ?: entry.contents.firstOrNull { !it.value.isNullOrBlank() }?.value
                ?: return null
        val text = Jsoup.parse(raw).text().replace(' ', ' ').replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty() || text.equals(entry.title?.trim(), ignoreCase = true)) return null
        if (text.length <= MAX_LENGTH) return text
        val cut = text.substring(0, MAX_LENGTH - 3)
        val lastSpace = cut.lastIndexOf(' ')
        return (if (lastSpace > 0) cut.substring(0, lastSpace) else cut).trimEnd(',', ';', ':') +
            "..."
    }
}
