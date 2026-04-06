/* Joseph B. Ottinger (C)2026 */
package dev.streampack.karma.service

import dev.streampack.karma.entity.KarmaRecord
import dev.streampack.karma.repository.KarmaRecordRepository
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest(properties = ["streampack.karma.max-subject-length=10"])
@Transactional
class KarmaServiceTests {

    @Autowired lateinit var karmaService: KarmaService
    @Autowired lateinit var repository: KarmaRecordRepository

    @Test
    fun `adjustKarma increments delta for new subject`() {
        val score = karmaService.adjustKarma("kotlin", 1)
        assertEquals(1, score)
    }

    @Test
    fun `adjustKarma on same day updates existing record`() {
        karmaService.adjustKarma("kotlin", 1)
        karmaService.adjustKarma("kotlin", 1)
        val score = karmaService.getKarma("kotlin")
        assertEquals(2, score)

        // Verify only one record exists for today
        val records = repository.findBySubject("kotlin")
        assertEquals(1, records.size)
        assertEquals(2, records[0].delta)
    }

    @Test
    fun `adjustKarma on different day creates new record`() {
        // Insert a record manually for yesterday
        repository.save(
            KarmaRecord(subject = "kotlin", recordDate = LocalDate.now().minusDays(1), delta = 3)
        )
        karmaService.adjustKarma("kotlin", 1)
        val records = repository.findBySubject("kotlin")
        assertEquals(2, records.size)
    }

    @Test
    fun `getKarma returns 0 for unknown subject`() {
        assertEquals(0, karmaService.getKarma("nonexistent"))
    }

    @Test
    fun `getKarma applies decay to old records`() {
        // Insert a record from 346 days ago (approximately one half-life)
        repository.save(
            KarmaRecord(
                subject = "oldtopic",
                recordDate = LocalDate.now().minusDays(346),
                delta = 2,
            )
        )
        val score = karmaService.getKarma("oldtopic")
        // exp(-0.002 * 346) ~= 0.5, so 2 * 0.5 = 1
        assertEquals(1, score)
    }

    @Test
    fun `hasKarma returns false for unknown subject`() {
        assertFalse(karmaService.hasKarma("nobody"))
    }

    @Test
    fun `hasKarma returns true for known subject`() {
        karmaService.adjustKarma("someone", 1)
        assertTrue(karmaService.hasKarma("someone"))
    }

    @Test
    fun `subjects are case-insensitive`() {
        karmaService.adjustKarma("Kotlin", 1)
        karmaService.adjustKarma("KOTLIN", 1)
        val score = karmaService.getKarma("kotlin")
        assertEquals(2, score)
    }

    @Test
    fun `cleanup removes oversized subjects`() {
        val longSubject = "supremely-long-subject"
        repository.save(KarmaRecord(subject = longSubject, recordDate = LocalDate.now(), delta = 5))

        karmaService.getKarma("someone-else")

        val remaining = repository.findBySubject(longSubject)
        assertTrue(remaining.isEmpty())
    }
}
