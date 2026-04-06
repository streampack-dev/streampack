/* Joseph B. Ottinger (C)2026 */
package dev.streampack.karma.service

import dev.streampack.karma.config.KarmaProperties
import dev.streampack.karma.entity.KarmaRecord
import dev.streampack.karma.model.KarmaLeaderboardEntry
import dev.streampack.karma.repository.KarmaRecordRepository
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.exp
import kotlin.math.roundToInt
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class KarmaService(
    private val repository: KarmaRecordRepository,
    private val karmaProperties: KarmaProperties,
) {
    private val logger = LoggerFactory.getLogger(KarmaService::class.java)

    /** Adjusts karma for a subject by the given increment, returns the current decayed score. */
    @Transactional
    fun adjustKarma(subject: String, increment: Int): Int {
        purgeStaleRecords()
        purgeOversizedSubjects()
        val normalized = subject.lowercase()
        val today = LocalDate.now()
        val existing = repository.findBySubjectAndRecordDate(normalized, today)
        if (existing != null) {
            repository.save(existing.copy(delta = existing.delta + increment))
        } else {
            repository.save(
                KarmaRecord(subject = normalized, recordDate = today, delta = increment)
            )
        }
        return getKarma(subject)
    }

    /** Computes the decayed karma score for a subject. Purges records older than 1 year. */
    @Transactional
    fun getKarma(subject: String): Int {
        purgeStaleRecords()
        purgeOversizedSubjects()
        val normalized = subject.lowercase()

        val records = repository.findBySubject(normalized)
        if (records.isEmpty()) return 0
        return computeScore(records, LocalDate.now())
    }

    /** Returns true if any karma records exist for the subject. */
    @Transactional(readOnly = true)
    fun hasKarma(subject: String): Boolean {
        return repository.findBySubject(subject.lowercase()).isNotEmpty()
    }

    /** Returns subjects ranked by decayed karma score, excluding zero-karma subjects */
    @Transactional
    fun getRanking(limit: Int, ascending: Boolean): List<Pair<String, Int>> {
        purgeStaleRecords()
        purgeOversizedSubjects()
        val now = LocalDate.now()
        val subjects = repository.findDistinctSubjects()
        return subjects
            .mapNotNull { subject ->
                val records = repository.findBySubject(subject)
                if (records.isEmpty()) return@mapNotNull null
                subject to computeScore(records, now)
            }
            .filter { it.second != 0 }
            .sortedBy { if (ascending) it.second else -it.second }
            .take(limit)
    }

    /** Rich leaderboard rows for HTTP clients. */
    @Transactional
    fun getLeaderboard(limit: Int, ascending: Boolean): List<KarmaLeaderboardEntry> {
        purgeStaleRecords()
        purgeOversizedSubjects()
        val now = LocalDate.now()
        return repository
            .findDistinctSubjects()
            .mapNotNull { subject ->
                val records = repository.findBySubject(subject)
                if (records.isEmpty()) return@mapNotNull null
                val score = computeScore(records, now)
                if (score == 0) return@mapNotNull null
                KarmaLeaderboardEntry(
                    subject = subject,
                    score = score,
                    lastUpdated = records.maxOf { it.recordDate },
                )
            }
            .sortedBy { if (ascending) it.score else -it.score }
            .take(limit)
    }

    private fun computeScore(records: List<KarmaRecord>, now: LocalDate): Int {
        val score =
            records.sumOf { record ->
                val ageInDays = ChronoUnit.DAYS.between(record.recordDate, now)
                record.delta * exp(-0.002 * ageInDays)
            }
        return score.roundToInt()
    }

    private fun purgeStaleRecords() {
        val cutoff = LocalDate.now().minusYears(1)
        val removed = repository.deleteByRecordDateBefore(cutoff)
        if (removed > 0) {
            logger.info("Removed {} karma record(s) older than {}", removed, cutoff)
        }
    }

    private fun purgeOversizedSubjects() {
        val removed = repository.purgeSubjectsLongerThan(karmaProperties.maxSubjectLength)
        if (removed > 0) {
            logger.info(
                "Removed {} karma record(s) exceeding max subject length {}",
                removed,
                karmaProperties.maxSubjectLength,
            )
        }
    }
}
