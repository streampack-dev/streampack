/* Joseph B. Ottinger (C)2026 */
package dev.streampack.irc.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TruncateForIrcTests {

    @Test
    fun `single line under limit is unchanged`() {
        val input = "hello world"
        assertEquals("hello world", IrcAdapter.truncateForIrc(input))
    }

    @Test
    fun `single line over 400 chars is truncated with suffix`() {
        val input = "a".repeat(500)
        val result = IrcAdapter.truncateForIrc(input)
        assertTrue(result.length <= 400)
        assertTrue(result.endsWith(" [...more]"))
    }

    @Test
    fun `single line at exactly 400 chars is unchanged`() {
        val input = "a".repeat(400)
        assertEquals(input, IrcAdapter.truncateForIrc(input))
    }

    @Test
    fun `multiline text returns first line with suffix`() {
        val input = "first line\nsecond line\nthird line"
        val result = IrcAdapter.truncateForIrc(input)
        assertEquals("first line [...more]", result)
    }

    @Test
    fun `multiline with long first line truncates and adds suffix`() {
        val longFirst = "a".repeat(500)
        val input = "$longFirst\nsecond line"
        val result = IrcAdapter.truncateForIrc(input)
        assertTrue(result.length <= 400)
        assertTrue(result.endsWith(" [...more]"))
        assertFalse(result.contains("\n"))
    }

    @Test
    fun `code block collapses to first line with suffix`() {
        val input = "```kotlin\nfun main() {\n    println(\"hello\")\n}\n```"
        val result = IrcAdapter.truncateForIrc(input)
        assertEquals("```kotlin [...more]", result)
    }

    @Test
    fun `two lines returns first line with suffix`() {
        val input = "line one\nline two"
        val result = IrcAdapter.truncateForIrc(input)
        assertEquals("line one [...more]", result)
    }

    @Test
    fun `empty second line still triggers multiline suffix`() {
        val input = "first line\n"
        val result = IrcAdapter.truncateForIrc(input)
        assertEquals("first line [...more]", result)
    }

    @Test
    fun `splitForIrc preserves multiline content across multiple messages`() {
        val input = "line one\nline two\nline three"
        val result = IrcAdapter.splitForIrc(input)
        assertEquals(listOf("line one", "line two", "line three"), result)
    }

    @Test
    fun `splitForIrc wraps long single line into multiple chunks`() {
        val input = "word ".repeat(120).trim()
        val result = IrcAdapter.splitForIrc(input)
        assertTrue(result.size > 1)
        assertTrue(result.all { it.length <= 400 })
    }

    @Test
    fun `splitForIrc applies more suffix when chunk count exceeds max lines`() {
        val input = (1..10).joinToString("\n") { "line $it" }
        val result = IrcAdapter.splitForIrc(input, maxLines = 3)
        assertEquals(3, result.size)
        assertTrue(result.last().endsWith(" [...more]"))
    }
}
