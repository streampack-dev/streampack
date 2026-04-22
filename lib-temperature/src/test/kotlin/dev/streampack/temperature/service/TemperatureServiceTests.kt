/* Joseph B. Ottinger (C)2026 */
package dev.streampack.temperature.service

import dev.streampack.temperature.repository.TemperatureBucketRepository
import dev.streampack.test.ResetDatabaseBeforeEach
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(properties = ["streampack.temperature.half-life-days=7"])
@ResetDatabaseBeforeEach
class TemperatureServiceTests {

    @Autowired lateinit var temperatureService: TemperatureService
    @Autowired lateinit var repository: TemperatureBucketRepository

    @Test
    fun `accrue creates and updates same-day bucket`() {
        val today = LocalDate.parse("2026-04-22")

        temperatureService.accrue("blog.post", "post-1", "hit", occurredOn = today)
        temperatureService.accrue("blog.post", "post-1", "hit", occurredOn = today)

        val bucket =
            repository.findByNamespaceAndSubjectKeyAndSignalAndBucketDate(
                "blog.post",
                "post-1",
                "hit",
                today,
            )

        assertEquals(2L, bucket!!.positiveDelta)
        assertEquals(0L, bucket.negativeDelta)
        assertEquals(2.0, temperatureService.score("blog.post", "post-1", "hit", today).netScore)
    }

    @Test
    fun `negative accrual reduces net temperature`() {
        val today = LocalDate.parse("2026-04-22")

        temperatureService.accrue(
            namespace = "karma.subject",
            subjectKey = "kotlin",
            signal = "vote",
            positiveDelta = 3L,
            negativeDelta = 1L,
            occurredOn = today,
        )

        val score = temperatureService.score("karma.subject", "kotlin", "vote", today)

        assertEquals(3.0, score.positiveScore)
        assertEquals(1.0, score.negativeScore)
        assertEquals(2.0, score.netScore)
    }

    @Test
    fun `recent activity outranks old spike after decay`() {
        val today = LocalDate.parse("2026-04-22")

        temperatureService.accrue(
            namespace = "blog.post",
            subjectKey = "old-post",
            signal = "hit",
            positiveDelta = 100L,
            occurredOn = today.minusDays(120),
        )
        temperatureService.accrue(
            namespace = "blog.post",
            subjectKey = "current-post",
            signal = "hit",
            positiveDelta = 10L,
            occurredOn = today,
        )

        val ranking = temperatureService.ranking("blog.post", "hit", today, size = 2).scores

        assertEquals("current-post", ranking.first().subjectKey)
        assertTrue(ranking.first().netScore > ranking.last().netScore)
    }

    @Test
    fun `ranking is scoped by namespace and signal`() {
        val today = LocalDate.parse("2026-04-22")

        temperatureService.accrue(
            "blog.post",
            "post-1",
            "hit",
            positiveDelta = 5L,
            occurredOn = today,
        )
        temperatureService.accrue(
            "rss.entry",
            "entry-1",
            "hit",
            positiveDelta = 20L,
            occurredOn = today,
        )
        temperatureService.accrue(
            "blog.post",
            "post-2",
            "vote",
            positiveDelta = 30L,
            occurredOn = today,
        )

        val ranking = temperatureService.ranking("blog.post", "hit", today, size = 10).scores

        assertEquals(listOf("post-1"), ranking.map { it.subjectKey })
    }

    @Test
    fun `ranking supports pagination and ascending order`() {
        val today = LocalDate.parse("2026-04-22")

        temperatureService.accrue(
            "blog.post",
            "post-1",
            "hit",
            positiveDelta = 1L,
            occurredOn = today,
        )
        temperatureService.accrue(
            "blog.post",
            "post-2",
            "hit",
            positiveDelta = 2L,
            occurredOn = today,
        )
        temperatureService.accrue(
            "blog.post",
            "post-3",
            "hit",
            positiveDelta = 3L,
            occurredOn = today,
        )

        val ranking =
            temperatureService.ranking(
                namespace = "blog.post",
                signal = "hit",
                asOf = today,
                page = 1,
                size = 2,
                ascending = true,
            )

        assertEquals(1, ranking.page)
        assertEquals(2, ranking.totalPages)
        assertEquals(3, ranking.totalCount)
        assertEquals(listOf("post-3"), ranking.scores.map { it.subjectKey })
    }
}
