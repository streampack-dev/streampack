/* Joseph B. Ottinger (C)2026 */
package dev.streampack.taxonomy

/**
 * Something that carries tags by name (#140): posts (`post_tags`), factoids (their `tags` lists).
 * The vocabulary reads what's in use through it and re-points uses when an admin aliases or splits
 * a tag, all in the admin action's transaction.
 */
interface TagUsages {
    /** What these are, for results and logs: `posts`, `factoids`. */
    val kind: String

    /**
     * Every tag in use here that the `tags` table may not have, as stored ([TagNames.stored]).
     * Empty for posts, whose tags are rows there already.
     */
    fun namesInUse(): Set<String> = emptySet()

    /**
     * Re-points every use of [from] (matched as [TagNames.stored] reads it) to [to]: one tag for an
     * alias, several for a split, each already a tag. An item carrying both keeps one of each.
     * Returns how many items changed.
     */
    fun retag(from: String, to: List<String>, actor: String): Int
}

/**
 * The canonical form of a suggested tag (#140): an alias becomes its tag, a stoplisted term is
 * dropped (null), and nothing is created or queued. For suggestion outputs (the AI and heuristic
 * suggesters), which only propose.
 */
fun interface TagCanonicalizer {
    fun canonical(raw: String?): String?
}
