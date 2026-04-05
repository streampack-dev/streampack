/* Joseph B. Ottinger (C)2026 */
package com.enigmastation.streampack.core.extensions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StringExtensionsTests {

    // --- compress ---

    @Test
    fun `compress trims leading and trailing whitespace`() {
        assertEquals("hello", "  hello  ".compress())
    }

    @Test
    fun `compress collapses internal whitespace runs`() {
        assertEquals("hello beautiful world", "hello   beautiful    world".compress())
    }

    @Test
    fun `compress handles tabs and newlines`() {
        assertEquals("a b c", "a\t\tb\n\nc".compress())
    }

    @Test
    fun `compress on empty string returns empty`() {
        assertEquals("", "".compress())
    }

    @Test
    fun `compress on whitespace-only string returns empty`() {
        assertEquals("", "   \t\n  ".compress())
    }

    // --- possessive ---

    @Test
    fun `possessive adds apostrophe-s for names not ending in s`() {
        assertEquals("joe's", "joe".possessive())
    }

    @Test
    fun `possessive adds only apostrophe for names ending in s`() {
        assertEquals("chris'", "chris".possessive())
    }

    @Test
    fun `possessive handles uppercase S`() {
        assertEquals("Ross'", "Ross".possessive())
    }

    @Test
    fun `possessive on empty string returns empty`() {
        assertEquals("", "".possessive())
    }

    @Test
    fun `possessive on single character`() {
        assertEquals("a's", "a".possessive())
    }

    // --- endsWithPunctuation ---

    @Test
    fun `endsWithPunctuation true for period`() {
        assertTrue("Done.".endsWithPunctuation())
    }

    @Test
    fun `endsWithPunctuation true for exclamation`() {
        assertTrue("Wow!".endsWithPunctuation())
    }

    @Test
    fun `endsWithPunctuation true for question mark`() {
        assertTrue("Really?".endsWithPunctuation())
    }

    @Test
    fun `endsWithPunctuation true for semicolon`() {
        assertTrue("clause;".endsWithPunctuation())
    }

    @Test
    fun `endsWithPunctuation true for colon`() {
        assertTrue("note:".endsWithPunctuation())
    }

    @Test
    fun `endsWithPunctuation false for letter`() {
        assertFalse("hello".endsWithPunctuation())
    }

    @Test
    fun `endsWithPunctuation false for comma`() {
        assertFalse("hello,".endsWithPunctuation())
    }

    @Test
    fun `endsWithPunctuation false for empty string`() {
        assertFalse("".endsWithPunctuation())
    }
}
