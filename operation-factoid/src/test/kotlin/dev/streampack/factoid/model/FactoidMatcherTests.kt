/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Finding the factoids prose mentions (#130). */
class FactoidMatcherTests {
    private fun catalog(vararg selectors: String, untexted: List<String> = emptyList()) =
        FactoidCatalog(
            selectors.map { FactoidCatalogEntry(it, "$it is something.") } +
                untexted.map { FactoidCatalogEntry(it, null) }
        )

    private fun FactoidMatcher.selectors(text: String) = find(text).map { it.selector }

    @Test
    fun `whole words only, ignoring case, as written`() {
        val matcher = FactoidMatcher(catalog("gradle", "osgi"))

        val found = matcher.find("Gradle builds it; gradlew wraps it; OSGi runs it.")

        assertEquals(listOf("gradle", "osgi"), found.map { it.selector })
        assertEquals(listOf("Gradle", "OSGi"), found.map { it.term })
        assertEquals(0, found[0].start)
    }

    @Test
    fun `the longest of overlapping mentions wins`() {
        val matcher = FactoidMatcher(catalog("spring", "spring boot"))

        assertEquals(
            listOf("spring boot", "spring"),
            matcher.selectors("Spring Boot sits on Spring."),
        )
    }

    @Test
    fun `a multi-word selector matches across any run of space`() {
        assertEquals(
            listOf("jep 401"),
            FactoidMatcher(catalog("jep 401")).selectors("see JEP\n401"),
        )
    }

    @Test
    fun `a selector is a whole word even next to punctuation, and not a prefix of a longer number`() {
        val matcher = FactoidMatcher(catalog("jep 3", "c++", "node.js"))

        assertEquals(
            listOf("c++", "node.js"),
            matcher.selectors("C++, then node.js (and JEP 310)."),
        )
    }

    @Test
    fun `factoids without text, numbers, parameters, phrasings and stopwords aren't matched`() {
        val matcher =
            FactoidMatcher(
                catalog(
                    "65",
                    "jwz \$1",
                    "define blue",
                    "ask why",
                    "go",
                    "idea",
                    "kotlin",
                    untexted = listOf("node"),
                )
            )

        assertEquals(
            listOf("kotlin"),
            matcher.selectors(
                "In Java 65 is class files; ask why; the idea is to go with node and Kotlin."
            ),
        )
    }

    @Test
    fun `the stopwords can be chosen`() {
        assertEquals(
            listOf("go"),
            FactoidMatcher(catalog("go"), stopwords = emptyList()).selectors("written in Go"),
        )
    }

    @Test
    fun `the catalog finds a factoid by selector, ignoring case`() {
        assertEquals("OSGi", catalog("OSGi").find(" osgi ")?.selector)
    }
}
