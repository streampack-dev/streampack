/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.entity

import dev.streampack.core.model.CodeChannel
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.UuidGenerator

/**
 * A short-lived numeric code sent over a [channel] to a [recipient] (an email address, a chat user
 * on a server, a phone number) for passwordless authentication
 */
@Entity
@Table(name = "one_time_codes")
data class OneTimeCode(
    @Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7) val id: UUID = UUID(0, 0),
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val channel: CodeChannel = CodeChannel.EMAIL,
    @Column(nullable = false) val recipient: String = "",
    @Column(nullable = false) val code: String = "",
    @Column(nullable = false) val expiresAt: Instant = Instant.now(),
    val usedAt: Instant? = null,
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
) {
    /** Whether this code can still be used for authentication */
    fun isValid(): Boolean = usedAt == null && Instant.now().isBefore(expiresAt)
}
