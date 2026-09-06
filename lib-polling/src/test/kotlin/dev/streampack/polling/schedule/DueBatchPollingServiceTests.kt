/* Joseph B. Ottinger (C)2026 */
package dev.streampack.polling.schedule

import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The template's contract, checked over an in-memory list of sources. */
class DueBatchPollingServiceTests {
    private data class Source(val name: String, val nextPollAt: Instant, val failures: Int = 0)

    private val now = Instant.parse("2026-09-06T12:00:00Z")
    private val interval = Duration.ofHours(1)

    private inner class Fake(batchSize: Int, private val failing: Set<String> = emptySet()) :
        DueBatchPollingService<Source>(Duration.ofSeconds(90), batchSize) {
        val sources = mutableListOf<Source>()
        val polled = mutableListOf<String>()

        override fun findDue(now: Instant, limit: Int): List<Source> =
            sources.filter { !it.nextPollAt.isAfter(now) }.sortedBy { it.nextPollAt }.take(limit)

        override fun poll(source: Source): PollResult<Source> {
            polled += source.name
            return if (source.name in failing) PollResult.Failure("boom")
            else PollResult.Success(source)
        }

        override fun scheduleAfterSuccess(source: Source, now: Instant) {
            replace(source.copy(nextPollAt = now.plus(interval), failures = 0))
        }

        override fun scheduleAfterFailure(source: Source, now: Instant, reason: String?) {
            val failures = source.failures + 1
            replace(
                source.copy(
                    nextPollAt =
                        PollSchedule.afterFailure(now, interval, failures, Duration.ofDays(1)),
                    failures = failures,
                )
            )
        }

        override fun describe(source: Source): String = source.name

        private fun replace(updated: Source) {
            sources.replaceAll { if (it.name == updated.name) updated else it }
        }
    }

    @Test
    fun `only the batch size is polled per tick, oldest due first, and the rest wait`() {
        val fake = Fake(batchSize = 2)
        fake.sources += Source("c", now.minusSeconds(10))
        fake.sources += Source("a", now.minusSeconds(3000))
        fake.sources += Source("b", now.minusSeconds(2000))
        fake.sources += Source("later", now.plusSeconds(150))

        assertEquals(2, fake.pollDue(now))
        assertEquals(listOf("a", "b"), fake.polled)
        assertEquals(1, fake.pollDue(now.plusSeconds(90)))
        assertEquals(listOf("a", "b", "c"), fake.polled)
        assertEquals(0, fake.pollDue(now.plusSeconds(120)))
        assertEquals(1, fake.pollDue(now.plusSeconds(200)))
        assertEquals("later", fake.polled.last())
    }

    @Test
    fun `a failing source backs off instead of staying due, and one failure does not stop the batch`() {
        val fake = Fake(batchSize = 5, failing = setOf("bad"))
        fake.sources += Source("bad", now.minusSeconds(100))
        fake.sources += Source("good", now.minusSeconds(50))

        fake.pollDue(now)
        assertEquals(listOf("bad", "good"), fake.polled)
        val bad = fake.sources.first { it.name == "bad" }
        assertEquals(1, bad.failures)
        assertEquals(now.plus(interval), bad.nextPollAt)

        fake.pollDue(now.plus(interval))
        assertEquals(2, fake.sources.first { it.name == "bad" }.failures)
        assertEquals(
            now.plus(interval).plus(Duration.ofHours(2)),
            fake.sources.first { it.name == "bad" }.nextPollAt,
        )
    }

    @Test
    fun `ticks only run a batch once per scheduler interval`() {
        val fake = Fake(batchSize = 5)
        fake.sources += Source("a", now.minusSeconds(10))
        fake.sources += Source("b", now.minusSeconds(10))
        fake.onTick(now)
        val first = fake.polled.size
        assertTrue(first == 2 || first == 0, "first tick polled $first")
        fake.sources += Source("c", now.minusSeconds(5))
        fake.onTick(now.plusSeconds(30))
        assertEquals(first, fake.polled.size)
        fake.onTick(now.plusSeconds(91))
        assertTrue(fake.polled.contains("c"), fake.polled.toString())
    }
}
