/* Joseph B. Ottinger (C)2026 */
package com.enigmastation.streampack.core.extensions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CollectionExtensionsTests {

    // --- joinToStringWithAnd ---

    @Test
    fun `joinToStringWithAnd on empty list returns empty`() {
        assertEquals("", emptyList<String>().joinToStringWithAnd())
    }

    @Test
    fun `joinToStringWithAnd on single element returns that element`() {
        assertEquals("alpha", listOf("alpha").joinToStringWithAnd())
    }

    @Test
    fun `joinToStringWithAnd on two elements uses and`() {
        assertEquals("alpha and beta", listOf("alpha", "beta").joinToStringWithAnd())
    }

    @Test
    fun `joinToStringWithAnd on three elements uses Oxford comma`() {
        assertEquals(
            "alpha, beta, and gamma",
            listOf("alpha", "beta", "gamma").joinToStringWithAnd(),
        )
    }

    @Test
    fun `joinToStringWithAnd on four elements uses Oxford comma`() {
        assertEquals(
            "alpha, beta, gamma, and delta",
            listOf("alpha", "beta", "gamma", "delta").joinToStringWithAnd(),
        )
    }

    // --- pluralize ---

    @Test
    fun `pluralize returns singular for single-element collection`() {
        assertEquals("item", "item".pluralize(listOf("one")))
    }

    @Test
    fun `pluralize returns plural for multi-element collection`() {
        assertEquals("items", "item".pluralize(listOf("one", "two")))
    }

    @Test
    fun `pluralize returns plural for empty collection`() {
        assertEquals("items", "item".pluralize(emptyList<String>()))
    }

    @Test
    fun `pluralize works with sets`() {
        assertEquals("entry", "entry".pluralize(setOf("only")))
        assertEquals("entrys", "entry".pluralize(setOf("a", "b")))
    }
}
