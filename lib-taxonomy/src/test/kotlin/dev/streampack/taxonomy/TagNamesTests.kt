/* Joseph B. Ottinger (C)2026 */
package dev.streampack.taxonomy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class TagNamesTests {

    @ParameterizedTest(name = "[{0}] -> [{1}]")
    @CsvSource(
        delimiter = '|',
        ignoreLeadingAndTrailingWhitespace = false,
        value =
            [
                "#c|c",
                "c#|c#",
                "C#|c#",
                "#C#|c#",
                "##c|#c",
                "load-testing|load testing",
                "Spring_Boot|spring boot",
                "  spring   boot |spring boot",
                "spring\tboot|spring boot",
                "spring - boot|spring boot",
                "-leading|leading",
                "trailing_|trailing",
                "c++|c++",
                "C++|c++",
                "日本語|日本語",
                "jakarta-ee|jakarta ee",
                "self-hosted|self hosted",
                "Java|java",
                "#Java|java",
                "#_idea|idea",
                "_idea|_idea",
                "_IDEA|_idea",
                "  _idea  |_idea",
                "_my-tag|_my-tag",
                ".net|.net",
                "node.js|node.js",
            ],
    )
    fun `normalize shapes a tag`(raw: String, expected: String) {
        assertEquals(expected, TagNames.normalize(raw))
    }

    @ParameterizedTest(name = "[{0}] -> null")
    @ValueSource(strings = ["", " ", "\t", "#", "-", "_", "__", "_-", "# ", "#-_", "--", " _ "])
    fun `normalize gives null when nothing is left`(raw: String) {
        assertNull(TagNames.normalize(raw))
    }

    @Test
    fun `normalize of null is null`() {
        assertNull(TagNames.normalize(null))
    }

    @ParameterizedTest(name = "[{0}] is already clean")
    @ValueSource(
        strings = ["spring boot", "java", "c#", "ai agents", "jvm", "c++", "kotlin", "_idea", "日本語"]
    )
    fun `clean tags normalize to themselves`(tag: String) {
        assertEquals(tag, TagNames.normalize(tag))
    }

    @Test
    fun `normalize is idempotent`() {
        listOf("#C#", "load-testing", "Spring_Boot", "  spring   boot ", "_IDEA", "c++").forEach {
            val once = TagNames.normalize(it)
            assertEquals(once, TagNames.normalize(once), "for [$it]")
        }
    }

    @Test
    fun `isSystem reports a leading underscore`() {
        assertTrue(TagNames.isSystem("_idea"))
        assertTrue(TagNames.isSystem(" _idea"))
        assertFalse(TagNames.isSystem("idea"))
        assertFalse(TagNames.isSystem("spring_boot"))
        assertFalse(TagNames.isSystem("c#"))
    }

    @Test
    fun `normalizeAll drops empties and repeats in order`() {
        assertEquals(
            listOf("load testing", "c#", "_idea"),
            TagNames.normalizeAll(listOf("Load-Testing", "#", "C#", "load testing", "_idea", null)),
        )
    }

    @Test
    fun `stored only trims and lowercases`() {
        assertEquals("self-hosted", TagNames.stored(" Self-Hosted "))
        assertEquals("_idea", TagNames.stored("_Idea"))
        assertNull(TagNames.stored("  "))
        assertNull(TagNames.stored(null))
    }

    @Test
    fun `splitStored reads a comma-separated list as stored`() {
        assertEquals(
            listOf("java", "self-hosted", "_idea"),
            TagNames.splitStored(" Java, self-hosted,,_idea "),
        )
        assertEquals(emptyList<String>(), TagNames.splitStored(null))
    }

    @Test
    fun `joinNormalized normalizes and de-duplicates a list to store`() {
        assertEquals(
            "java,load testing,c#",
            TagNames.joinNormalized("Java, load-testing,#c#, java,LOAD_TESTING".split(',')),
        )
    }
}
