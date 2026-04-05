/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.UuidGenerator

/** Token used for email verification and password reset flows */
@Entity
@Table(name = "verification_tokens")
data class VerificationToken(
    @Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7) val id: UUID = UUID(0, 0),
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User = User(),
    @Column(nullable = false, unique = true) val token: String = "",
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val tokenType: TokenType = TokenType.EMAIL_VERIFICATION,
    @Column(nullable = false) val expiresAt: Instant = Instant.now(),
    val usedAt: Instant? = null,
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
) {
    /** Checks whether this token is still usable */
    fun isValid(): Boolean = usedAt == null && Instant.now().isBefore(expiresAt)
}
