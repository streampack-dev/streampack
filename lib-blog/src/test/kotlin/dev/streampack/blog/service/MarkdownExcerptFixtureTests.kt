/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.service

import java.nio.file.Files
import java.nio.file.Paths
import java.util.stream.Stream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class MarkdownExcerptFixtureTests {
    val logger = LoggerFactory.getLogger(this::class.java)

    @Autowired lateinit var markdownRenderingService: MarkdownRenderingService

    @ParameterizedTest(name = "excerpt fixture: {0}")
    @MethodSource("fixtureCases")
    fun `excerpt matches resource fixtures`(
        name: String,
        markdown: String,
        expectedSummary: String?,
    ) {
        val actual = markdownRenderingService.excerpt(markdown, maxLength = 5000, maxSentences = 3)
        logger.info("Actual: {}", actual)
        if (expectedSummary != null) {
            assertEquals(expectedSummary, actual, "Fixture mismatch for $name")
        } else {
            val sentenceCount = sentenceCount(actual)
            assertTrue(actual.isNotBlank(), "Summary should not be blank for $name")
            assertTrue(sentenceCount <= 3, "Summary should have <= 3 sentences for $name")
        }
    }

    private fun sentenceCount(text: String): Int {
        val breaker = java.text.BreakIterator.getSentenceInstance(java.util.Locale.US)
        breaker.setText(text)
        var count = 0
        var start = breaker.first()
        var end = breaker.next()
        while (end != java.text.BreakIterator.DONE) {
            if (text.substring(start, end).trim().isNotEmpty()) {
                count++
            }
            start = end
            end = breaker.next()
        }
        return count
    }

    companion object {
        @JvmStatic
        fun fixtureCases(): Stream<Arguments> {
            val rootUrl =
                requireNotNull(
                    MarkdownExcerptFixtureTests::class.java.classLoader.getResource("excerpts")
                ) {
                    "Missing excerpts fixture directory in test resources"
                }
            val rootPath = Paths.get(rootUrl.toURI())
            val cases =
                Files.list(rootPath).use { paths ->
                    paths
                        .filter { it.fileName.toString().endsWith(".md") }
                        .sorted()
                        .map { markdownPath ->
                            val baseName = markdownPath.fileName.toString().removeSuffix(".md")
                            val expectedPath = rootPath.resolve("$baseName.expected.txt")
                            val markdown = Files.readString(markdownPath)
                            val expected =
                                if (Files.exists(expectedPath)) {
                                    Files.readString(expectedPath).trim()
                                } else {
                                    null
                                }
                            Arguments.of(baseName, markdown, expected)
                        }
                        .toList()
                }
            return cases.stream()
        }
    }
}
