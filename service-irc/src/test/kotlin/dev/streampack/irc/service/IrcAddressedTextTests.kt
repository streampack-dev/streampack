/* Joseph B. Ottinger (C)2026 */
package dev.streampack.irc.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class IrcAddressedTextTests {

    @Test
    fun `signal-prefixed message is stripped`() {
        val stripped = IrcAdapter.stripAddressedText("!sentiment #primate", "!", "nevet")
        assertEquals("sentiment #primate", stripped)
    }

    @Test
    fun `nick-prefixed message is stripped`() {
        val stripped = IrcAdapter.stripAddressedText("nevet: sentiment #primate", "!", "nevet")
        assertEquals("sentiment #primate", stripped)
    }

    @Test
    fun `non-addressed message returns null`() {
        val stripped = IrcAdapter.stripAddressedText("sentiment #primate", "!", "nevet")
        assertNull(stripped)
    }
}
