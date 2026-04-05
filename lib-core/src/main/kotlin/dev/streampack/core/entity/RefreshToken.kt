/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.UuidGenerator

/** A long-lived opaque token used to obtain new JWTs after the access token expires */
@Entity
@Table(name = "refresh_tokens")
data class RefreshToken(
    @Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7) val id: UUID = UUID(0, 0),
    @Column(nullable = false) val userId: UUID = UUID(0, 0),
    @Column(nullable = false) val tokenHash: String = "",
    @Column(nullable = false) val expiresAt: Instant = Instant.now(),
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
)
