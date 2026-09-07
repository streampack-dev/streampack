/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mattermost.service

import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReconnectBackoffTests {
    @Test
    fun `delay doubles per failed attempt and caps`() {
        val base = Duration.ofSeconds(15)
        val cap = Duration.ofMinutes(5)
        assertEquals(Duration.ofSeconds(15), ReconnectBackoff.delay(1, base, cap))
        assertEquals(Duration.ofSeconds(30), ReconnectBackoff.delay(2, base, cap))
        assertEquals(Duration.ofSeconds(60), ReconnectBackoff.delay(3, base, cap))
        assertEquals(Duration.ofMinutes(4), ReconnectBackoff.delay(5, base, cap))
        assertEquals(cap, ReconnectBackoff.delay(6, base, cap))
        assertEquals(cap, ReconnectBackoff.delay(40, base, cap))
        assertEquals(Duration.ofSeconds(15), ReconnectBackoff.delay(0, base, cap))
    }
}
