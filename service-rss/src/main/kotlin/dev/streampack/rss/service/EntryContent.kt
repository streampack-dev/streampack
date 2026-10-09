/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.service

import com.rometools.rome.feed.synd.SyndEntry
import org.jsoup.Jsoup

/**
 * An entry's full content as plain text (#187): its RSS `content:encoded` or Atom `<content>`, HTML
 * reduced to its text and whitespace collapsed, cut at a word to [MAX_LENGTH]. Null when the feed
 * gave none. Kept for the rating guess only; never public.
 */
object EntryContent {
    const val MAX_LENGTH = 20_000

    fun of(entry: SyndEntry): String? {
        val raw =
            entry.contents
                .mapNotNull { it.value?.takeIf(String::isNotBlank) }
                .joinToString("\n")
                .ifBlank { null } ?: return null
        val text = plain(raw)
        return text.takeIf { it.isNotEmpty() }?.let { cut(it, MAX_LENGTH) }
    }

    /** [html]'s text, whitespace collapsed. */
    fun plain(html: String): String =
        Jsoup.parse(html).text().replace(' ', ' ').replace(Regex("\\s+"), " ").trim()

    /** [text] cut at a word to at most [max] characters, marked with `...` when it was cut. */
    fun cut(text: String, max: Int): String {
        if (text.length <= max) return text
        val head = text.substring(0, max - 3)
        val lastSpace = head.lastIndexOf(' ')
        val kept = if (lastSpace > max / 2) head.substring(0, lastSpace) else head
        return kept.trimEnd(',', ';', ':', ' ') + "..."
    }
}
