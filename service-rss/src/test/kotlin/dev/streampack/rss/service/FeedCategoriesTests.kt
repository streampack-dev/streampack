/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** A feed entry's own tags, kept from RSS and Atom and normalized (#139). */
class FeedCategoriesTests {
    @Test
    fun `RSS category elements are kept, as written and normalized`() {
        val feed =
            FeedTagSamples.parse(
                FeedTagSamples.rss(
                    "https://a.example",
                    mapOf("1" to listOf("Spring-Boot", "#Kotlin", "load_testing", "  Java  ")),
                )
            )
        assertEquals(
            listOf(
                FeedCategory("spring boot", "Spring-Boot"),
                FeedCategory("kotlin", "#Kotlin"),
                FeedCategory("load testing", "load_testing"),
                FeedCategory("java", "Java"),
            ),
            FeedCategories.of(feed.entries.single()),
        )
    }

    @Test
    fun `Atom category terms are kept, not their labels`() {
        val feed =
            FeedTagSamples.parse(
                FeedTagSamples.atom("https://b.example", mapOf("1" to listOf("Kotlin", "C#")))
            )
        assertEquals(
            listOf(FeedCategory("kotlin", "Kotlin"), FeedCategory("c#", "C#")),
            FeedCategories.of(feed.entries.single()),
        )
    }

    @Test
    fun `one per name, the first form kept, and nothing that isn't a tag`() {
        val tooLong = "x".repeat(FeedCategories.MAX_NAME + 1)
        val feed =
            FeedTagSamples.parse(
                FeedTagSamples.rss(
                    "https://a.example",
                    mapOf("1" to listOf("Spring Boot", "spring-boot", "#", "-", tooLong, "c++")),
                )
            )
        assertEquals(
            listOf(FeedCategory("spring boot", "Spring Boot"), FeedCategory("c++", "c++")),
            FeedCategories.of(feed.entries.single()),
        )
    }

    @Test
    fun `an entry with no categories has none`() {
        val feed =
            FeedTagSamples.parse(FeedTagSamples.rss("https://a.example", mapOf("1" to listOf())))
        assertEquals(emptyList<FeedCategory>(), FeedCategories.of(feed.entries.single()))
    }
}
