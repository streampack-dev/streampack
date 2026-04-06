/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.repository

import dev.streampack.core.entity.RefreshToken
import java.time.Instant
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

/** Persistence for long-lived refresh tokens */
interface RefreshTokenRepository : JpaRepository<RefreshToken, UUID> {

    fun findByTokenHash(tokenHash: String): RefreshToken?

    /** Removes all refresh tokens that have expired */
    @Modifying
    @Query("DELETE FROM RefreshToken r WHERE r.expiresAt < :cutoff")
    fun deleteExpired(cutoff: Instant): Int

    /** Removes all refresh tokens for a given user (for logout or account erasure) */
    @Modifying
    @Query("DELETE FROM RefreshToken r WHERE r.userId = :userId")
    fun deleteByUserId(userId: UUID): Int
}
