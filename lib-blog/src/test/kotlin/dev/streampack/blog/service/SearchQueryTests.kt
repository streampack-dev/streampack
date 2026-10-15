/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Quoted words and phrases match exactly; everything else is left to stemming (#227). */
class SearchQueryTests {
    private fun groups(query: String) = SearchQuery.parse(query).exactGroups

    @Test
    fun `an unquoted query has nothing exact`() {
        val query = SearchQuery.parse("emergent behaviour")
        assertEquals(emptyList<List<String>>(), query.exactGroups)
        assertNull(query.postgresPattern())
        assertFalse(query.hasExact)
    }

    @Test
    fun `a quoted word and a quoted phrase are exact, beside stemmed words`() {
        assertEquals(
            listOf(listOf("emergent", "kotlin coroutines")),
            groups("emergent \"emergent\" \"kotlin  coroutines\" threads"),
        )
    }

    @Test
    fun `an unclosed quote runs to the end, as websearch_to_tsquery reads it`() {
        assertEquals(listOf(listOf("virtual threads")), groups("java \"virtual threads"))
    }

    @Test
    fun `a negated or empty quote adds nothing exact`() {
        assertEquals(emptyList<List<String>>(), groups("java -\"virtual threads\" \"\""))
    }

    @Test
    fun `or splits the query into alternatives, each with its own exact phrases`() {
        assertEquals(
            listOf(listOf("emergent"), listOf("emerging")),
            groups("\"emergent\" OR \"emerging\""),
        )
    }

    @Test
    fun `an alternative with nothing exact leaves the whole query to stemming`() {
        val query = SearchQuery.parse("\"emergent\" or emerging")
        assertFalse(query.hasExact)
        assertNull(query.postgresPattern())
    }

    @Test
    fun `or inside quotes is part of the phrase`() {
        assertEquals(listOf(listOf("this or that")), groups("\"this or that\""))
    }

    @Test
    fun `the postgres pattern needs every phrase of one alternative, at word edges`() {
        assertEquals(
            "(?:^(?=.*\\memergent\\M)(?=.*\\mkotlin\\s+coroutines\\M))|(?:^(?=.*\\memerging\\M))",
            SearchQuery.parse("\"emergent\" \"kotlin coroutines\" or \"emerging\"")
                .postgresPattern(),
        )
    }

    @Test
    fun `regex characters are escaped, and edges that aren't word characters get no boundary`() {
        assertEquals("(?:^(?=.*\\mC\\+\\+))", SearchQuery.parse("\"C++\"").postgresPattern())
        assertEquals("(?:^(?=.*\\.NET\\M))", SearchQuery.parse("\".NET\"").postgresPattern())
    }

    @Test
    fun `the java patterns find exact words only, ignoring case`() {
        val patterns = SearchQuery.parse("\"emergent\" \"kotlin coroutines\"").javaPatterns()
        assertTrue(patterns[0].containsMatchIn("Emergent behaviour"))
        assertFalse(patterns[0].containsMatchIn("emerging behaviour"))
        assertFalse(patterns[0].containsMatchIn("nonemergently"))
        assertTrue(patterns[1].containsMatchIn("Kotlin\ncoroutines"))
    }
}
