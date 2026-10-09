/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.service

import com.rometools.rome.feed.synd.SyndEntry
import dev.streampack.taxonomy.TagNames

/** A feed entry's own tag: [name] normalized, [raw] as the feed wrote it. */
data class FeedCategory(val name: String, val raw: String)

/**
 * A feed entry's own tags (#139). ROME reads both RSS `<category>` and Atom `<category term>` into
 * [SyndEntry.categories]. Each is normalized ([TagNames.normalize]); one per name, the first form
 * kept. A name that normalizes to nothing, or runs past [MAX_NAME], isn't a tag; nor is a system
 * tag (`_idea`): only the site adds those, never a feed.
 */
object FeedCategories {
    /** Longer than any tag in use: past it, a "category" is a sentence, not a tag. */
    const val MAX_NAME = 100

    /** The column `raw` is stored in. */
    private const val MAX_RAW = 255

    fun of(entry: SyndEntry): List<FeedCategory> =
        entry.categories
            .orEmpty()
            .mapNotNull { category ->
                val raw =
                    category?.name?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val name = TagNames.normalize(raw) ?: return@mapNotNull null
                if (name.length > MAX_NAME || TagNames.isSystem(name)) null
                else FeedCategory(name, raw.take(MAX_RAW))
            }
            .distinctBy { it.name }
}
