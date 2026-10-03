/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.repository

import dev.streampack.core.entity.MessageLog
import dev.streampack.core.model.MessageDirection
import java.time.Instant
import java.util.UUID
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface MessageLogRepository : JpaRepository<MessageLog, UUID> {
    fun findByProvenanceUriOrderByTimestampDesc(
        provenanceUri: String,
        pageable: Pageable,
    ): Page<MessageLog>

    /** Returns messages within a time window in chronological order */
    fun findByProvenanceUriAndTimestampBetweenOrderByTimestampAsc(
        provenanceUri: String,
        from: Instant,
        to: Instant,
        pageable: Pageable,
    ): Page<MessageLog>

    /**
     * Messages in one provenance whose content contains [pattern] (an ILIKE pattern, its own
     * wildcards escaped with a backslash), newest first. The trigram index on content serves it.
     */
    @Query(
        value =
            """
            SELECT * FROM message_log
            WHERE provenance_uri = :provenanceUri
              AND content ILIKE :pattern ESCAPE '\'
            ORDER BY timestamp DESC, id DESC
            """,
        countQuery =
            """
            SELECT count(*) FROM message_log
            WHERE provenance_uri = :provenanceUri
              AND content ILIKE :pattern ESCAPE '\'
            """,
        nativeQuery = true,
    )
    fun searchContent(provenanceUri: String, pattern: String, pageable: Pageable): Page<MessageLog>

    /** Returns recent messages by a sender on a given protocol, case-insensitive */
    @Query(
        """
        SELECT m FROM MessageLog m
        WHERE LOWER(m.sender) = LOWER(:sender)
          AND m.direction = :direction
          AND m.provenanceUri LIKE :protocolPrefix
        ORDER BY m.timestamp DESC
        """
    )
    fun findRecentBySenderOnProtocol(
        sender: String,
        direction: MessageDirection,
        protocolPrefix: String,
        pageable: Pageable,
    ): Page<MessageLog>
}
