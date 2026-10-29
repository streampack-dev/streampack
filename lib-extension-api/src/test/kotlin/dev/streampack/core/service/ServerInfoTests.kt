/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ServerInfoTests {
    private fun server(version: String?) =
        object : ServerInfo {
            override val version = version
        }

    @Test
    fun `versions compare by their numbers, not as text`() {
        assertTrue(ServerInfo.compare("1.16.10", "1.16.9") > 0)
        assertTrue(ServerInfo.compare("1.9", "1.16") < 0)
        assertEquals(0, ServerInfo.compare("1.16", "1.16.0"))
        assertEquals(0, ServerInfo.compare("1.16.11-SNAPSHOT", "1.16.11"))
    }

    @Test
    fun `a server is at least its own version and older ones`() {
        assertTrue(server("1.16.11").isAtLeast("1.16.11"))
        assertTrue(server("1.17.0").isAtLeast("1.16.11"))
        assertFalse(server("1.16.10").isAtLeast("1.16.11"))
    }

    @Test
    fun `a development build with no version counts as new enough`() {
        assertTrue(server(null).isAtLeast("99.0"))
    }

    @Test
    fun `something that isn't a version is refused`() {
        assertThrows<IllegalArgumentException> { ServerInfo.compare("1.x", "1.0") }
    }
}
