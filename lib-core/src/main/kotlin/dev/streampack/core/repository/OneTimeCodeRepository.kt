/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.repository

import dev.streampack.core.entity.OneTimeCode
import dev.streampack.core.model.CodeChannel
import java.time.Instant
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

/** Persistence for one-time authentication codes, keyed by channel and recipient */
interface OneTimeCodeRepository : JpaRepository<OneTimeCode, UUID> {
    fun findByChannelAndRecipientAndCode(
        channel: CodeChannel,
        recipient: String,
        code: String,
    ): OneTimeCode?

    /** Counts codes that have not been used and have not expired */
    @Query(
        "SELECT COUNT(c) FROM OneTimeCode c WHERE c.channel = :channel AND c.recipient = :recipient " +
            "AND c.usedAt IS NULL AND c.expiresAt > :now"
    )
    fun countActive(channel: CodeChannel, recipient: String, now: Instant): Long

    /** Removes all codes for a recipient on a channel (for account erasure) */
    @Modifying
    @Query("DELETE FROM OneTimeCode c WHERE c.channel = :channel AND c.recipient = :recipient")
    fun deleteByChannelAndRecipient(channel: CodeChannel, recipient: String)

    /** Removes used and expired codes in one pass */
    @Modifying
    @Query("DELETE FROM OneTimeCode c WHERE c.expiresAt < :cutoff OR c.usedAt IS NOT NULL")
    fun deleteStale(cutoff: Instant): Int

    /** Removes used and expired codes for one recipient on a channel */
    @Modifying
    @Query(
        "DELETE FROM OneTimeCode c WHERE c.channel = :channel AND c.recipient = :recipient " +
            "AND (c.expiresAt < :cutoff OR c.usedAt IS NOT NULL)"
    )
    fun deleteStaleFor(channel: CodeChannel, recipient: String, cutoff: Instant): Int

    /** Atomically consumes a valid code by deleting it */
    @Modifying
    @Query(
        "DELETE FROM OneTimeCode c WHERE c.channel = :channel AND c.recipient = :recipient " +
            "AND c.code = :code AND c.usedAt IS NULL AND c.expiresAt > :now"
    )
    fun consumeValidCode(channel: CodeChannel, recipient: String, code: String, now: Instant): Int
}
