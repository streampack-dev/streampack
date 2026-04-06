/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.entity.MessageLog
import dev.streampack.core.model.MessageDirection
import dev.streampack.core.repository.MessageLogRepository
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service

/**
 * Captures all messages to the protocol-agnostic message log. Failures never disrupt processing.
 */
@Service
class MessageLogService(private val repository: MessageLogRepository) {
    private val logger = LoggerFactory.getLogger(MessageLogService::class.java)

    fun logInbound(provenanceUri: String, sender: String, content: String) {
        log(provenanceUri, MessageDirection.INBOUND, sender, content)
    }

    fun logOutbound(provenanceUri: String, sender: String, content: String) {
        log(provenanceUri, MessageDirection.OUTBOUND, sender, content)
    }

    /** Returns messages for a provenance within a time window, in chronological order */
    fun findMessages(
        provenanceUri: String,
        from: Instant,
        to: Instant,
        limit: Int,
    ): List<MessageLog> {
        return repository
            .findByProvenanceUriAndTimestampBetweenOrderByTimestampAsc(
                provenanceUri,
                from,
                to,
                PageRequest.of(0, limit),
            )
            .content
    }

    /** Returns recent inbound messages from a sender on a given protocol */
    fun findRecentMessagesBySender(
        sender: String,
        protocolPrefix: String,
        limit: Int,
    ): List<MessageLog> {
        return repository
            .findRecentBySenderOnProtocol(
                sender,
                MessageDirection.INBOUND,
                "$protocolPrefix%",
                PageRequest.of(0, limit),
            )
            .content
    }

    /** Returns the most recent message for a provenance, if any. */
    fun findLatestMessage(provenanceUri: String): MessageLog? {
        return repository
            .findByProvenanceUriOrderByTimestampDesc(provenanceUri, PageRequest.of(0, 1))
            .content
            .firstOrNull()
    }

    private fun log(
        provenanceUri: String,
        direction: MessageDirection,
        sender: String,
        content: String,
    ) {
        try {
            repository.save(
                MessageLog(
                    provenanceUri = provenanceUri,
                    direction = direction,
                    sender = sender,
                    content = content,
                )
            )
        } catch (e: Exception) {
            logger.error("Failed to log {} message for {}: {}", direction, provenanceUri, e.message)
        }
    }
}
