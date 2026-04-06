/* Joseph B. Ottinger (C)2026 */
package dev.streampack.karma.repository

import dev.streampack.karma.entity.KarmaRecord
import java.time.LocalDate
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface KarmaRecordRepository : JpaRepository<KarmaRecord, UUID> {
    fun findBySubjectAndRecordDate(subject: String, recordDate: LocalDate): KarmaRecord?

    fun findBySubject(subject: String): List<KarmaRecord>

    fun deleteByRecordDateBefore(cutoff: LocalDate): Int

    @Modifying
    @Query("DELETE FROM KarmaRecord k WHERE length(k.subject) > :maxLength")
    fun purgeSubjectsLongerThan(maxLength: Int): Int

    @Query("SELECT DISTINCT k.subject FROM KarmaRecord k") fun findDistinctSubjects(): List<String>
}
