/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.config.StreampackProperties
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Log days follow the configured zone (#212) */
class LogDaysTests {
    private val newYork = LogDays(ZoneId.of("America/New_York"))

    @Test
    fun `a line belongs to the day it was in the configured zone`() {
        assertEquals(
            LocalDate.of(2026, 10, 13),
            newYork.dayOf(Instant.parse("2026-10-14T03:00:00Z")),
        )
        assertEquals(
            LocalDate.of(2026, 10, 14),
            newYork.dayOf(Instant.parse("2026-10-14T05:00:00Z")),
        )
    }

    @Test
    fun `a day starts and ends at local midnight`() {
        val day = LocalDate.of(2026, 10, 14)
        assertEquals(Instant.parse("2026-10-14T04:00:00Z"), newYork.start(day))
        assertEquals(Instant.parse("2026-10-15T04:00:00Z"), newYork.end(day))
    }

    @Test
    fun `daylight-saving change days are 23 and 25 hours long`() {
        val spring = LocalDate.of(2026, 3, 8)
        val fall = LocalDate.of(2026, 11, 1)
        assertEquals(
            Duration.ofHours(23),
            Duration.between(newYork.start(spring), newYork.end(spring)),
        )
        assertEquals(Duration.ofHours(25), Duration.between(newYork.start(fall), newYork.end(fall)))
    }

    @Test
    fun `the default zone is UTC`() {
        val days = LogDays(StreampackProperties())
        assertEquals(ZoneId.of("UTC"), days.zone)
        assertEquals(LocalDate.of(2026, 10, 14), days.dayOf(Instant.parse("2026-10-14T00:00:00Z")))
        assertEquals(
            LocalDate.of(2026, 10, 14).atStartOfDay().toInstant(ZoneOffset.UTC),
            days.start(LocalDate.of(2026, 10, 14)),
        )
    }

    @Test
    fun `a zone that isn't one stops startup, naming the setting`() {
        val e =
            assertThrows<SilentStartupException> {
                LogDays(
                    StreampackProperties(logs = StreampackProperties.LogsProperties("Mars/Olympus"))
                )
            }
        assertTrue(e.message!!.contains("STREAMPACK_LOG_ZONE"), e.message)
        assertTrue(e.message!!.contains("Mars/Olympus"), e.message)
    }
}
