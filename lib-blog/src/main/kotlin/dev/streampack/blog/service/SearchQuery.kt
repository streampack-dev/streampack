/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.service

/**
 * The exact part of a post search (#227). The query goes to `websearch_to_tsquery` as typed, which
 * stems every word, quoted or not; a quoted word or phrase must also appear exactly, ignoring case,
 * at word edges. `or` separates alternatives, as in websearch syntax: a post needs the exact
 * phrases of one alternative. An alternative with none leaves the query to stemming alone. A
 * negated quote (`-"x"`) adds nothing: stemming already excludes it.
 */
class SearchQuery private constructor(val exactGroups: List<List<String>>) {
    /** Whether results must contain some phrase exactly. */
    val hasExact: Boolean
        get() = exactGroups.isNotEmpty()

    /**
     * A Postgres ARE for `~*`: one alternative per group, each requiring all its phrases by
     * lookahead. Null when nothing is exact.
     */
    fun postgresPattern(): String? =
        if (!hasExact) null
        else
            exactGroups.joinToString("|") { phrases ->
                "(?:^" + phrases.joinToString("") { "(?=.*${phrase(it, "\\m", "\\M")})" } + ")"
            }

    /** Every exact phrase as a Kotlin regex, ignoring case, for marking it in a passage. */
    fun javaPatterns(): List<Regex> =
        exactGroups.flatten().distinct().map {
            Regex(
                phrase(it, "(?<![\\p{L}\\p{N}_])", "(?![\\p{L}\\p{N}_])"),
                RegexOption.IGNORE_CASE,
            )
        }

    private fun phrase(text: String, start: String, end: String): String {
        val words = text.split(WHITESPACE)
        val body = words.joinToString("\\s+") { escape(it) }
        return (if (isWordChar(text.first())) start else "") +
            body +
            (if (isWordChar(text.last())) end else "")
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")
        private const val SPECIAL = "\\^$.|?*+()[]{}"

        private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_'

        private fun escape(word: String) = buildString {
            word.forEach {
                if (it in SPECIAL) append('\\')
                append(it)
            }
        }

        fun parse(query: String): SearchQuery {
            val groups = mutableListOf<MutableList<String>>(mutableListOf())
            var i = 0
            while (i < query.length) {
                val c = query[i]
                when {
                    c.isWhitespace() -> i++
                    c == '"' || (c == '-' && query.getOrNull(i + 1) == '"') -> {
                        val negated = c == '-'
                        val open = if (negated) i + 1 else i
                        val close =
                            query.indexOf('"', open + 1).let { if (it < 0) query.length else it }
                        val text = query.substring(open + 1, close).trim().replace(WHITESPACE, " ")
                        if (!negated && text.isNotEmpty()) groups.last().add(text)
                        i = close + 1
                    }
                    else -> {
                        var end = i
                        while (
                            end < query.length && !query[end].isWhitespace() && query[end] != '"'
                        ) end++
                        if (query.substring(i, end).equals("or", ignoreCase = true)) {
                            groups.add(mutableListOf())
                        }
                        i = end
                    }
                }
            }
            // An alternative with no exact phrase can be met by stemming alone.
            if (groups.any { it.isEmpty() }) return SearchQuery(emptyList())
            return SearchQuery(groups.map { it.distinct() })
        }
    }
}
