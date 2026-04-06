/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.service

import dev.streampack.factoid.model.FactoidAttributeType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

/** Integration tests for FactoidTextRenderer selection and reference resolution */
@SpringBootTest
@Transactional
class FactoidTextRendererTests {

    @Autowired lateinit var renderer: FactoidTextRenderer
    @Autowired lateinit var factoidService: FactoidService

    @Test
    fun `text without selections passes through unchanged`() {
        assertEquals("hello world", renderer.resolveSelections("hello world", 3))
    }

    @Test
    fun `parentheses without pipe pass through unchanged`() {
        assertEquals("(just a note)", renderer.resolveSelections("(just a note)", 3))
    }

    @Test
    fun `selection group picks from options`() {
        val validResults = setOf("hello", "world")
        repeat(20) {
            val result = renderer.resolveSelections("(hello|world)", 3)
            assertTrue(result in validResults)
        }
    }

    @Test
    fun `multiple selection groups resolve independently`() {
        val validA = setOf("a", "b")
        val validC = setOf("c", "d")
        repeat(20) {
            val result = renderer.resolveSelections("(a|b) and (c|d)", 3)
            val parts = result.split(" and ")
            assertEquals(2, parts.size)
            assertTrue(parts[0] in validA)
            assertTrue(parts[1] in validC)
        }
    }

    @Test
    fun `tilde reference resolves factoid text`() {
        factoidService.save("target", FactoidAttributeType.TEXT, "resolved value", "test")

        val validResults = setOf("resolved value", "literal")
        repeat(20) {
            val result = renderer.resolveSelections("(~target|literal)", 3)
            assertTrue(result in validResults)
        }
    }

    @Test
    fun `tilde reference strips reply prefix`() {
        factoidService.save("target", FactoidAttributeType.TEXT, "<reply>clean value", "test")

        val result = renderer.resolveReference("target", 3)
        assertEquals("clean value", result)
    }

    @Test
    fun `tilde reference miss returns empty string in selection`() {
        // Force the ~ path with only tilde references
        val result = renderer.resolveSelections("(~missing|~missing)", 3)
        assertEquals("", result)
    }

    @Test
    fun `hops exhausted returns value unchanged`() {
        assertEquals("(~target|literal)", renderer.resolveSelections("(~target|literal)", 0))
    }

    @Test
    fun `resolveReference returns null when hops exhausted`() {
        assertNull(renderer.resolveReference("anything", 0))
    }

    @Test
    fun `resolveReference returns null for missing factoid`() {
        assertNull(renderer.resolveReference("missing", 3))
    }

    @Test
    fun `chained reference resolution works`() {
        factoidService.save("inner", FactoidAttributeType.TEXT, "<reply>deep value", "test")
        factoidService.save("outer", FactoidAttributeType.TEXT, "<reply>(~inner|~inner)", "test")

        val result = renderer.resolveReference("outer", 3)
        assertEquals("deep value", result)
    }

    // -- Nested parentheses --

    @Test
    fun `nested parentheses resolve correctly`() {
        val validOuter = setOf("Yes", "No", "Ask me tomorrow", "Ask me again")
        repeat(50) {
            val result = renderer.resolveSelections("(Yes|No|Ask me( tomorrow| again))", 3)
            assertTrue(result in validOuter, "Unexpected result: $result")
        }
    }

    @Test
    fun `deeply nested groups with surrounding text`() {
        val validInner = setOf("unclear", "unknowable")
        val validOuter =
            setOf(
                "Yes",
                "No",
                "Maybe",
                "Ask me tomorrow",
                "Ask me again",
                "Ask me again tomorrow",
                "It's unclear",
                "It's unknowable",
            )
        repeat(50) {
            val result =
                renderer.resolveSelections(
                    "(Yes|No|Maybe|Ask me( tomorrow| again| again tomorrow)|It's (unclear|unknowable))",
                    3,
                )
            assertTrue(result in validOuter, "Unexpected result: $result")
        }
    }

    @Test
    fun `8ball expression with trailing punctuation`() {
        val input =
            "(Yes|No|Maybe|Ask me( tomorrow| again| again tomorrow)|It's (unclear|unknowable))."
        repeat(50) {
            val result = renderer.resolveSelections(input, 3)
            // Should always end with a period and never contain unresolved | or )
            assertTrue(result.endsWith("."), "Missing period: $result")
            assertTrue("|" !in result, "Unresolved pipe: $result")
            assertTrue(")" !in result, "Unresolved paren: $result")
        }
    }

    // -- Empty options --

    @Test
    fun `empty option is valid`() {
        val validResults = setOf("hello", "")
        repeat(20) {
            val result = renderer.resolveSelections("(hello|)", 3)
            assertTrue(result in validResults, "Unexpected result: '$result'")
        }
    }

    @Test
    fun `leading empty option is valid`() {
        val validResults = setOf("", "world")
        repeat(20) {
            val result = renderer.resolveSelections("(|world)", 3)
            assertTrue(result in validResults, "Unexpected result: '$result'")
        }
    }

    // -- Edge cases --

    @Test
    fun `unbalanced open paren passes through`() {
        assertEquals("(oops", renderer.resolveSelections("(oops", 3))
    }

    @Test
    fun `selection embedded in surrounding text`() {
        val validResults = setOf("I say hello to you", "I say goodbye to you")
        repeat(20) {
            val result = renderer.resolveSelections("I say (hello|goodbye) to you", 3)
            assertTrue(result in validResults, "Unexpected result: $result")
        }
    }
}
