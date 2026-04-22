/* Joseph B. Ottinger (C)2026 */
package dev.streampack.temperature.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.hibernate.annotations.UuidGenerator

/** Daily positive/negative temperature accrual for one namespaced subject and signal. */
@Entity
@Table(name = "temperature_buckets")
data class TemperatureBucket(
    @Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7) val id: UUID = UUID(0, 0),
    @Column(nullable = false, length = 120) val namespace: String = "",
    @Column(nullable = false, length = 512) val subjectKey: String = "",
    @Column(nullable = false, length = 120) val signal: String = "",
    @Column(nullable = false) val bucketDate: LocalDate = LocalDate.now(),
    @Column(nullable = false) val positiveDelta: Long = 0,
    @Column(nullable = false) val negativeDelta: Long = 0,
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
    @Column(nullable = false) val updatedAt: Instant = Instant.now(),
)
