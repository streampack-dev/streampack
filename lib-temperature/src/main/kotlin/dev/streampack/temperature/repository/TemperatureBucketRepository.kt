/* Joseph B. Ottinger (C)2026 */
package dev.streampack.temperature.repository

import dev.streampack.temperature.entity.TemperatureBucket
import java.time.LocalDate
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface TemperatureBucketRepository : JpaRepository<TemperatureBucket, UUID> {
    fun findByNamespaceAndSubjectKeyAndSignalAndBucketDate(
        namespace: String,
        subjectKey: String,
        signal: String,
        bucketDate: LocalDate,
    ): TemperatureBucket?

    fun findByNamespaceAndSubjectKeyAndSignal(
        namespace: String,
        subjectKey: String,
        signal: String,
    ): List<TemperatureBucket>

    @Query(
        "SELECT DISTINCT bucket.subjectKey FROM TemperatureBucket bucket " +
            "WHERE bucket.namespace = :namespace AND bucket.signal = :signal"
    )
    fun findDistinctSubjectKeys(namespace: String, signal: String): List<String>

    @Modifying
    @Query("DELETE FROM TemperatureBucket bucket WHERE bucket.bucketDate < :cutoff")
    fun deleteByBucketDateBefore(cutoff: LocalDate): Int
}
