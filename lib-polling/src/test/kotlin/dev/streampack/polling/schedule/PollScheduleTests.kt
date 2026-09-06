/* Joseph B. Ottinger (C)2026 */
package dev.streampack.polling.schedule

import java.time.Duration
import java.time.Instant
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PollScheduleTests {
    private val now = Instant.parse("2026-09-06T12:00:00Z")
    private val hour = Duration.ofHours(1)

    @Test
    fun `success schedules one interval out plus bounded jitter`() {
        val zero = PollSchedule.afterSuccess(now, hour, random = Random(0))
        assertTrue(!zero.isBefore(now.plus(hour)), zero.toString())
        assertTrue(!zero.isAfter(now.plus(hour).plus(Duration.ofMinutes(6))), zero.toString())
        repeat(50) { seed ->
            val next = PollSchedule.afterSuccess(now, hour, random = Random(seed))
            assertTrue(
                next >= now.plus(hour) && next <= now.plus(Duration.ofMinutes(66)),
                next.toString(),
            )
        }
    }

    @Test
    fun `failures back off exponentially and cap`() {
        val cap = Duration.ofHours(6)
        assertEquals(
            now.plus(hour),
            PollSchedule.afterFailure(now, hour, failures = 1, maxBackoff = cap),
        )
        assertEquals(now.plus(Duration.ofHours(2)), PollSchedule.afterFailure(now, hour, 2, cap))
        assertEquals(now.plus(Duration.ofHours(4)), PollSchedule.afterFailure(now, hour, 3, cap))
        assertEquals(now.plus(cap), PollSchedule.afterFailure(now, hour, 4, cap))
        assertEquals(now.plus(cap), PollSchedule.afterFailure(now, hour, 40, cap))
        assertEquals(now.plus(hour), PollSchedule.afterFailure(now, hour, 0, cap))
    }

    @Test
    fun `initial spread lands inside one interval`() {
        repeat(50) { seed ->
            val next = PollSchedule.spread(now, hour, Random(seed))
            assertTrue(next >= now && next < now.plus(hour), next.toString())
        }
    }
}
