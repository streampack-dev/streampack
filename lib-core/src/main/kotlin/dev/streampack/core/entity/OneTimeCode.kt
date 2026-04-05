/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.UuidGenerator

/** A short-lived numeric code sent to an email address for passwordless authentication */
@Entity
@Table(name = "one_time_codes")
data class OneTimeCode(
    @Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7) val id: UUID = UUID(0, 0),
    @Column(nullable = false) val email: String = "",
    @Column(nullable = false) val code: String = "",
    @Column(nullable = false) val expiresAt: Instant = Instant.now(),
    val usedAt: Instant? = null,
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
) {
    /** Whether this code can still be used for authentication */
    fun isValid(): Boolean = usedAt == null && Instant.now().isBefore(expiresAt)
}
