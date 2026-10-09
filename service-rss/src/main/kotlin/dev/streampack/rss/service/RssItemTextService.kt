/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.service

import com.rometools.rome.feed.synd.SyndEntry
import dev.streampack.core.fetch.ArticleText
import dev.streampack.core.fetch.GuardedFetcher
import dev.streampack.rss.entity.RssEntry
import dev.streampack.rss.entity.RssEntryText
import dev.streampack.rss.entity.RssTextSource
import dev.streampack.rss.repository.RssEntryTextRepository
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service

/** Reads a feed item's article page as plain text; null when it has none or can't be fetched. */
fun interface RssItemPageReader {
    fun read(url: String): String?
}

/**
 * Fetches the page through the [GuardedFetcher] (public addresses only, its timeouts and the bot's
 * user agent, no retries) and extracts its text with [ArticleText], as `suggest <url>` does.
 */
@Component
class GuardedRssItemPageReader(private val fetcher: GuardedFetcher) : RssItemPageReader {
    private val logger = LoggerFactory.getLogger(GuardedRssItemPageReader::class.java)

    override fun read(url: String): String? =
        try {
            val response = fetcher.get(url, mapOf("Accept" to "text/html,application/xhtml+xml"))
            val type = response.header("Content-Type").orEmpty().lowercase()
            if (!response.successful) {
                logger.info("RSS item page {}: HTTP {}", url, response.status)
                null
            } else if (type.isNotEmpty() && "html" !in type) {
                logger.info("RSS item page {}: not HTML ({})", url, type)
                null
            } else ArticleText.extract(response.body, response.finalUri.toString())?.text
        } catch (e: Exception) {
            logger.info("RSS item page {} couldn't be read: {}", url, e.message)
            null
        }
}

/**
 * Feed items' full text, for the rating guess (#187). The feed's own content is kept as an item is
 * stored; when there's none, the article page is fetched the first time a guess needs it, once, and
 * its text kept (or the fact it had none). Never public.
 */
@Service
class RssItemTextService(
    private val texts: RssEntryTextRepository,
    private val pageReader: RssItemPageReader,
) {
    private val logger = LoggerFactory.getLogger(RssItemTextService::class.java)

    /** The text a guess is given for an item, and where it came from. */
    data class ItemText(val source: RssTextSource, val text: String)

    /**
     * Keeps the feed's own content of each newly stored entry that has any. A failure is logged and
     * never stops a poll.
     */
    fun recordContent(stored: List<Pair<RssEntry, SyndEntry>>, now: Instant = Instant.now()) {
        try {
            keepContent(stored, now)
        } catch (e: Exception) {
            logger.warn("Couldn't keep the content of {} feed item(s): {}", stored.size, e.message)
        }
    }

    private fun keepContent(stored: List<Pair<RssEntry, SyndEntry>>, now: Instant) {
        val kept = stored.mapNotNull { (entry, synd) ->
            EntryContent.of(synd)?.let {
                RssEntryText(
                    entryId = entry.id,
                    source = RssTextSource.CONTENT,
                    content = it,
                    storedAt = now,
                )
            }
        }
        if (kept.isNotEmpty()) texts.saveAll(kept)
    }

    /**
     * [entry]'s text for the guess, cut at a word to [PROMPT_LENGTH]: its feed content, else its
     * page's (fetched now if it never has been, and kept), else its summary, else nothing but its
     * title.
     */
    fun textFor(entry: RssEntry, now: Instant = Instant.now()): ItemText {
        val kept = texts.findById(entry.id).orElse(null) ?: fetchPage(entry, now)
        kept.content?.let {
            return ItemText(kept.source, EntryContent.cut(it, PROMPT_LENGTH))
        }
        entry.summary
            ?.takeIf { it.isNotBlank() }
            ?.let {
                return ItemText(RssTextSource.SUMMARY, EntryContent.cut(it, PROMPT_LENGTH))
            }
        return ItemText(RssTextSource.TITLE, "")
    }

    private fun fetchPage(entry: RssEntry, now: Instant): RssEntryText {
        val page = pageReader.read(entry.link)?.takeIf { it.isNotBlank() }
        return texts.save(
            RssEntryText(
                entryId = entry.id,
                source = if (page != null) RssTextSource.PAGE else RssTextSource.NONE,
                content = page?.let { EntryContent.cut(it, EntryContent.MAX_LENGTH) },
                storedAt = now,
            )
        )
    }

    companion object {
        /** The most of an item's text a guess is given. */
        const val PROMPT_LENGTH = 4_000
    }
}
