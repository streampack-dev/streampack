/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.extensions

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
}
