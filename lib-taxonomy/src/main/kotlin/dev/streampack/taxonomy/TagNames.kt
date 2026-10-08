/* Joseph B. Ottinger (C)2026 */
package dev.streampack.taxonomy

/**
 * The one shape a tag name takes, for posts and factoids alike (#140).
 *
 * [normalize] is for tags being written: what an author, an editor or the AI proposes. It
 * lowercases, drops one leading `#` (`#c` is `c`, `c#` stays `c#`), turns `-` and `_` into spaces
 * (`load-testing` is `load testing`) and collapses whitespace. A name starting with `_` is a system
 * tag (`_idea`): it is only trimmed and lowercased, and [isSystem] reports it.
 *
 * [stored] is for tags being read back: trimmed and lowercased, as the SQL that counts and matches
 * stored tags reads them. Readers use it rather than [normalize] so that what they report stays in
 * step with that SQL and with the tags already stored (a stored `self-hosted` is still read as
 * `self-hosted`).
 *
 * Callers keep their own policy on top: minimum lengths, caps, and dropping system tags where a
 * list is only suggestions or is shown publicly.
 */
object TagNames {
    /** What a system tag starts with. */
    const val SYSTEM_PREFIX = "_"

    private val SEPARATORS = Regex("[-_]")
    private val WHITESPACE = Regex("(?U)\\s+")
    private val WORD = Regex("[\\p{L}\\p{N}]")

    /**
     * The tag [raw] names, in the one shape tags are written in, or null if nothing is left of it.
     *
     * - trimmed and lowercased;
     * - a system tag (a leading `_`, as `_idea`) is kept as it is, lowercased, and is null unless a
     *   letter or digit follows the `_`;
     * - otherwise one leading `#` is dropped and any other kept (`#c#` is `c#`);
     * - `-` and `_` become spaces, whitespace runs become one space, and the ends are trimmed.
     *
     * Symbols and letters outside Latin are kept: `c++` is `c++`, `日本語` is `日本語`. A `_` after a
     * dropped `#` doesn't make a system tag: `#_idea` is `idea`.
     */
    fun normalize(raw: String?): String? {
        val text = raw?.trim()?.lowercase() ?: return null
        if (text.startsWith(SYSTEM_PREFIX)) {
            return text.takeIf { WORD.containsMatchIn(it.substring(SYSTEM_PREFIX.length)) }
        }
        return text
            .removePrefix("#")
            .replace(SEPARATORS, " ")
            .replace(WHITESPACE, " ")
            .trim()
            .ifEmpty { null }
    }

    /** Every tag in [raws] normalized, without the empty ones and without repeats, in order. */
    fun normalizeAll(raws: Iterable<String?>): List<String> =
        raws.mapNotNull(::normalize).distinct()

    /** Whether [tag] is a system tag, such as `_idea`: one the public never sees. */
    fun isSystem(tag: String): Boolean = tag.trimStart().startsWith(SYSTEM_PREFIX)

    /**
     * A stored tag as it's read back: trimmed and lowercased, or null if blank. This matches the
     * `TRIM(LOWER(...))` the factoid tag SQL uses, so it does not reshape tags stored before
     * [normalize] existed.
     */
    fun stored(raw: String?): String? = raw?.trim()?.lowercase()?.ifEmpty { null }

    /** A stored comma-separated tag list (a factoid's `tags`), each read with [stored]. */
    fun splitStored(csv: String?): List<String> = csv.orEmpty().split(',').mapNotNull(::stored)

    /** [raws] as a comma-separated tag list to store: normalized, de-duplicated, in order. */
    fun joinNormalized(raws: Iterable<String?>): String = normalizeAll(raws).joinToString(",")
}
