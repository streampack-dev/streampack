/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.model

/**
 * Finds the factoids a piece of prose mentions (#130): plain dictionary matching of selectors as
 * whole words, ignoring case, no guessing at meaning. Only factoids with definition text are
 * matched, and not those that aren't concepts at all: numbers (the class-file entries), selectors
 * with a parameter (`jwz $1`), chat phrasings (`define …`, `ask …`), or those in [stopwords],
 * everyday words that happen to name a factoid (`go`, `idea`, `rest`). Where mentions overlap, the
 * longest wins: "Spring Boot" is `spring boot`, not `spring`.
 *
 * Shared by the editor's suggestions, drafting a factoid's see-also and the factoid graph report.
 */
class FactoidMatcher(catalog: FactoidCatalog, stopwords: Collection<String> = DEFAULT_STOPWORDS) {
    private val stop = stopwords.map { it.lowercase().trim() }.toSet()
    private val matchable: List<Pair<FactoidCatalogEntry, Regex>> =
        catalog.entries
            .filter { entry -> !entry.text.isNullOrBlank() && matchable(entry.selector) }
            .sortedByDescending { it.selector.length }
            .map { entry -> entry to wholeWord(entry.selector) }

    /** A factoid mentioned: as written ([term]), where, and which factoid. */
    data class Mention(val selector: String, val term: String, val start: Int) {
        val end: Int
            get() = start + term.length
    }

    /** Every mention in [text], in order, overlapping ones resolved to the longest. */
    fun find(text: String): List<Mention> {
        val taken = BooleanArray(text.length)
        val found = mutableListOf<Mention>()
        // Longest selectors first, so a shorter one inside a longer mention finds its place taken.
        for ((entry, pattern) in matchable) {
            for (match in pattern.findAll(text)) {
                val range = match.range
                if (range.any { taken[it] }) continue
                range.forEach { taken[it] = true }
                found += Mention(entry.selector, match.value, range.first)
            }
        }
        return found.sortedBy { it.start }
    }

    private fun matchable(selector: String): Boolean {
        val s = selector.lowercase().trim()
        return s.isNotEmpty() &&
            s !in stop &&
            !s.all { it.isDigit() } &&
            '$' !in s &&
            COMMAND_PREFIXES.none { s.startsWith(it) }
    }

    /** [selector] as a whole word: not inside a longer word, spaces matching any run of space. */
    private fun wholeWord(selector: String): Regex {
        val body = selector.trim().split(Regex("\\s+")).joinToString("\\s+") { Regex.escape(it) }
        return Regex("(?<![\\p{L}\\p{N}_])$body(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
    }

    companion object {
        private val COMMAND_PREFIXES = listOf("define ", "ask ")

        /** Everyday words that name a factoid, which prose uses in their everyday sense. */
        val DEFAULT_STOPWORDS =
            listOf(
                "ab",
                "advice",
                "ant",
                "blitz",
                "bun",
                "go",
                "hey",
                "idea",
                "ignite",
                "locust",
                "mill",
                "parcel",
                "react",
                "rest",
                "savant",
                "siege",
                "tags",
                "toon",
            )
    }
}
