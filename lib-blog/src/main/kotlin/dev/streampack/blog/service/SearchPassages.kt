/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.service

import dev.streampack.blog.model.SearchMark
import dev.streampack.blog.model.SearchMatch
import jakarta.persistence.EntityManager
import java.util.UUID
import org.springframework.stereotype.Service

/**
 * The passage that shows why each search result matched (#226). Fields are tried in order (body,
 * excerpt, title, tags), and the first with a match gives the passage. A stemmed query uses
 * Postgres's `ts_headline` with the search's own `websearch_to_tsquery`, so the marked words are
 * the ones that matched, `emerging` for `emergent`. A query with quoted words marks those words
 * exactly, as they're what the result had to contain (#227).
 */
@Service
class SearchPassages(private val entityManager: EntityManager) {
    fun forPosts(ids: List<UUID>, query: String, parsed: SearchQuery): Map<UUID, SearchMatch> {
        if (ids.isEmpty()) return emptyMap()
        return if (parsed.hasExact) exact(ids, parsed) else stemmed(ids, query)
    }

    private fun stemmed(ids: List<UUID>, query: String): Map<UUID, SearchMatch> {
        val rows =
            entityManager
                .createNativeQuery(
                    """
                    SELECT p.id,
                           ts_headline('english', html_text(p.rendered_html), q, :options),
                           ts_headline('english', coalesce(p.excerpt, ''), q, :options),
                           ts_headline('english', p.title, q, :options),
                           ts_headline('english', posts_search_tags(p.id), q, :options)
                    FROM posts p, websearch_to_tsquery('english', :query) q
                    WHERE p.id IN (:ids)
                    """,
                )
                .setParameter("ids", ids)
                .setParameter("query", query)
                .setParameter("options", OPTIONS)
                .resultList
        return rows
            .map { it as Array<*> }
            .mapNotNull { row ->
                val id = row[0] as UUID
                SOURCES.indices
                    .firstNotNullOfOrNull { i -> marked(SOURCES[i], row[i + 1] as String?) }
                    ?.let { id to it }
            }
            .toMap()
    }

    private fun exact(ids: List<UUID>, parsed: SearchQuery): Map<UUID, SearchMatch> {
        val patterns = parsed.javaPatterns()
        val rows =
            entityManager
                .createNativeQuery(
                    """
                    SELECT p.id, html_text(p.rendered_html), coalesce(p.excerpt, ''), p.title,
                           posts_search_tags(p.id)
                    FROM posts p
                    WHERE p.id IN (:ids)
                    """,
                )
                .setParameter("ids", ids)
                .resultList
        return rows
            .map { it as Array<*> }
            .mapNotNull { row ->
                val id = row[0] as UUID
                SOURCES.indices
                    .firstNotNullOfOrNull { i ->
                        passage(SOURCES[i], (row[i + 1] as String?).orEmpty(), patterns)
                    }
                    ?.let { id to it }
            }
            .toMap()
    }

    companion object {
        private val SOURCES = listOf("body", "excerpt", "title", "tags")

        // Private-use characters stand in for the markup ts_headline would add: they can't be in a
        // post's text, and they leave nothing to escape.
        private const val START = ''
        private const val STOP = ''
        private const val ELLIPSIS = " … "
        private const val OPTIONS =
            "StartSel=, StopSel=, MaxWords=30, MinWords=12, ShortWord=3, " +
                "MaxFragments=2, FragmentDelimiter=\"$ELLIPSIS\""

        /** Words of context kept before and after an exact match. */
        private const val BEFORE = 12
        private const val AFTER = 18

        /** ts_headline's output as text and marks; null when it marked nothing. */
        fun marked(source: String, headline: String?): SearchMatch? {
            if (headline == null || START !in headline) return null
            val text = StringBuilder()
            val marks = mutableListOf<SearchMark>()
            var open = -1
            for (c in headline) {
                when (c) {
                    START -> open = text.length
                    STOP -> {
                        if (open >= 0 && text.length > open)
                            marks.add(SearchMark(open, text.length))
                        open = -1
                    }
                    else -> text.append(c)
                }
            }
            val raw = text.toString()
            val lead = raw.length - raw.trimStart().length
            val trimmed = raw.trim()
            return SearchMatch(
                source,
                trimmed,
                marks.map {
                    SearchMark(it.start - lead, (it.end - lead).coerceAtMost(trimmed.length))
                },
            )
        }

        /**
         * A window of words around the first exact match in [text], with every exact match in it
         * marked; null when there's none.
         */
        fun passage(source: String, text: String, patterns: List<Regex>): SearchMatch? {
            val first =
                patterns.mapNotNull { it.find(text) }.minByOrNull { it.range.first } ?: return null
            val words = Regex("\\S+").findAll(text).map { it.range }.toList()
            val firstWord = words.indexOfFirst { it.last >= first.range.first }
            val lastWord = words.indexOfLast { it.first <= first.range.last }
            val from = (firstWord - BEFORE).coerceAtLeast(0)
            val to = (lastWord + AFTER).coerceAtMost(words.lastIndex)
            val start = words[from].first
            val end = words[to].last + 1
            val prefix = if (from > 0) ELLIPSIS.trimStart() else ""
            val suffix = if (to < words.lastIndex) ELLIPSIS.trimEnd() else ""
            val window = text.substring(start, end)
            val marks =
                patterns
                    .flatMap { pattern -> pattern.findAll(window).map { it.range }.toList() }
                    .sortedBy { it.first }
                    .fold(mutableListOf<IntRange>()) { kept, range ->
                        if (kept.isEmpty() || range.first > kept.last().last) kept.add(range)
                        kept
                    }
                    .map { SearchMark(prefix.length + it.first, prefix.length + it.last + 1) }
            return SearchMatch(source, prefix + window + suffix, marks)
        }
    }
}
