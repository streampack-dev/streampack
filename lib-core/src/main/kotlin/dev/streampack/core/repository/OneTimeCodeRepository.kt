/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.repository

import com.enigmastation.streampack.core.entity.OneTimeCode
import java.time.Instant
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

/** Persistence for one-time authentication codes */
interface OneTimeCodeRepository : JpaRepository<OneTimeCode, UUID> {

    fun findByEmailAndCode(email: String, code: String): OneTimeCode?

    /** Counts codes that have not been used and have not expired */
    @Query(
        "SELECT COUNT(c) FROM OneTimeCode c WHERE c.email = :email AND c.usedAt IS NULL AND c.expiresAt > :now"
    )
    fun countActiveByEmail(email: String, now: Instant): Long

    /** Removes all codes for a given email address (for account erasure) */
    @Modifying
    @Query("DELETE FROM OneTimeCode c WHERE c.email = :email")
    fun deleteByEmail(email: String)

    /** Removes codes that expired before the given cutoff */
    @Modifying
    @Query("DELETE FROM OneTimeCode c WHERE c.expiresAt < :cutoff")
    fun deleteExpired(cutoff: Instant)

    /** Removes used and expired codes in one pass */
    @Modifying
    @Query("DELETE FROM OneTimeCode c WHERE c.expiresAt < :cutoff OR c.usedAt IS NOT NULL")
    fun deleteStale(cutoff: Instant): Int

    /** Removes used and expired codes for a specific email */
    @Modifying
    @Query(
        "DELETE FROM OneTimeCode c WHERE c.email = :email AND (c.expiresAt < :cutoff OR c.usedAt IS NOT NULL)"
    )
    fun deleteStaleByEmail(email: String, cutoff: Instant): Int

    /** Atomically consumes a valid code by deleting it */
    @Modifying
    @Query(
        "DELETE FROM OneTimeCode c WHERE c.email = :email AND c.code = :code " +
            "AND c.usedAt IS NULL AND c.expiresAt > :now"
    )
    fun consumeValidCode(email: String, code: String, now: Instant): Int
}
