/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.model

/**
 * An admin's judgment of a feed item (#187): [RATES] is worth writing about, [MIGHT] perhaps, and
 * [DULL] not. The model's hidden guess uses the same three.
 */
enum class RssRating {
    RATES,
    MIGHT,
    DULL;

    companion object {
        /** The rating named [name], in any case; null for anything else. */
        fun parse(name: String?): RssRating? =
            name?.trim()?.uppercase()?.let { n -> entries.firstOrNull { it.name == n } }
    }
}
