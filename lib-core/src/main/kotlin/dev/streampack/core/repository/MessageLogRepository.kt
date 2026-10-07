/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.repository

import dev.streampack.core.entity.MessageLog
import dev.streampack.core.model.MessageDirection
import java.time.Instant
import java.util.UUID
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional

/**
 * The message log. Direct and hidden entries are never returned: entity queries are restricted by
 * [MessageLog] itself, and every native query here says `AND NOT direct AND NOT hidden`; a new
 * native query must too. The moderation queries at the end are the one exception for hidden lines:
 * an admin reviewing a report has to see what was hidden. They still never touch a direct one.
 */
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

    /** Returns messages within a time window, newest first */
    fun findByProvenanceUriAndTimestampBetweenOrderByTimestampDesc(
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
              AND NOT direct
              AND NOT hidden
              AND content ILIKE :pattern ESCAPE '\'
            ORDER BY timestamp DESC, id DESC
            """,
        countQuery =
            """
            SELECT count(*) FROM message_log
            WHERE provenance_uri = :provenanceUri
              AND NOT direct
              AND NOT hidden
              AND content ILIKE :pattern ESCAPE '\'
            """,
        nativeQuery = true,
    )
    fun searchContent(provenanceUri: String, pattern: String, pageable: Pageable): Page<MessageLog>

    /** As [searchContent], only the lines [sender] wrote (the nick as logged, ignoring case). */
    @Query(
        value =
            """
            SELECT * FROM message_log
            WHERE provenance_uri = :provenanceUri
              AND NOT direct
              AND NOT hidden
              AND lower(sender) = lower(:sender)
              AND content ILIKE :pattern ESCAPE '\'
            ORDER BY timestamp DESC, id DESC
            """,
        countQuery =
            """
            SELECT count(*) FROM message_log
            WHERE provenance_uri = :provenanceUri
              AND NOT direct
              AND NOT hidden
              AND lower(sender) = lower(:sender)
              AND content ILIKE :pattern ESCAPE '\'
            """,
        nativeQuery = true,
    )
    fun searchContentBySender(
        provenanceUri: String,
        sender: String,
        pattern: String,
        pageable: Pageable,
    ): Page<MessageLog>

    /**
     * Every line [sender] wrote in one provenance (the nick as logged, ignoring case), newest
     * first.
     */
    @Query(
        value =
            """
            SELECT * FROM message_log
            WHERE provenance_uri = :provenanceUri
              AND NOT direct
              AND NOT hidden
              AND lower(sender) = lower(:sender)
            ORDER BY timestamp DESC, id DESC
            """,
        countQuery =
            """
            SELECT count(*) FROM message_log
            WHERE provenance_uri = :provenanceUri
              AND NOT direct
              AND NOT hidden
              AND lower(sender) = lower(:sender)
            """,
        nativeQuery = true,
    )
    fun findBySender(provenanceUri: String, sender: String, pageable: Pageable): Page<MessageLog>

    /** Returns recent messages by a sender on a given protocol, case-insensitive */
    @Query(
        """
        SELECT m FROM MessageLog m
        WHERE LOWER(m.sender) = LOWER(:sender)
          AND m.direction = :direction
          AND m.provenanceUri LIKE :protocolPrefix
          AND m.direct = false
          AND m.hidden = false
        ORDER BY m.timestamp DESC
        """
    )
    fun findRecentBySenderOnProtocol(
        sender: String,
        direction: MessageDirection,
        protocolPrefix: String,
        pageable: Pageable,
    ): Page<MessageLog>

    /**
     * Moderation only: these lines, hidden ones included, oldest first. Direct lines are never
     * returned, whatever ids are asked for.
     */
    @Query(
        value =
            """
            SELECT * FROM message_log
            WHERE id IN (:ids)
              AND NOT direct
            ORDER BY timestamp ASC, id ASC
            """,
        nativeQuery = true,
    )
    fun findForModeration(ids: Collection<UUID>): List<MessageLog>

    /**
     * Moderation only: a channel's lines in a time window, hidden ones included, oldest first.
     * Direct lines are never returned.
     */
    @Query(
        value =
            """
            SELECT * FROM message_log
            WHERE provenance_uri = :provenanceUri
              AND NOT direct
              AND timestamp >= :from
              AND timestamp < :to
            ORDER BY timestamp ASC, id ASC
            LIMIT :limit
            """,
        nativeQuery = true,
    )
    fun findWindowForModeration(
        provenanceUri: String,
        from: Instant,
        to: Instant,
        limit: Int,
    ): List<MessageLog>

    /**
     * Moderation only: hides or unhides these lines, returning how many changed. A direct line is
     * never touched.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value =
            """
            UPDATE message_log SET hidden = :hidden
            WHERE id IN (:ids)
              AND NOT direct
              AND hidden <> :hidden
            """,
        nativeQuery = true,
    )
    fun setHiddenForModeration(ids: Collection<UUID>, hidden: Boolean): Int

    /**
     * Moderation only: deletes these lines for good, returning how many went. A direct line is
     * never touched.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = "DELETE FROM message_log WHERE id IN (:ids) AND NOT direct",
        nativeQuery = true,
    )
    fun purgeForModeration(ids: Collection<UUID>): Int
}
