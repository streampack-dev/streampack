/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.entity.MessageLog
import dev.streampack.core.model.MessageDirection
import dev.streampack.core.model.MessageKind
import dev.streampack.core.repository.MessageLogRepository
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service

/**
 * Captures all messages to the protocol-agnostic message log. Failures never disrupt processing.
 */
@Service
class MessageLogService(private val repository: MessageLogRepository) {
    private val logger = LoggerFactory.getLogger(MessageLogService::class.java)

    /**
     * Logs a message received, or an event of the given [kind]. A [direct] one is kept, but nothing
     * here ever returns it.
     */
    fun logInbound(
        provenanceUri: String,
        sender: String,
        content: String,
        direct: Boolean = false,
        kind: MessageKind = MessageKind.MESSAGE,
    ) {
        log(provenanceUri, MessageDirection.INBOUND, sender, content, direct, kind)
    }

    /** Logs a message sent. A [direct] one is kept, but nothing here ever returns it. */
    fun logOutbound(
        provenanceUri: String,
        sender: String,
        content: String,
        direct: Boolean = false,
    ) {
        log(provenanceUri, MessageDirection.OUTBOUND, sender, content, direct, MessageKind.MESSAGE)
    }

    /**
     * Returns lines for a provenance within a time window, in chronological order: those of the
     * given [kinds], every kind unless told otherwise.
     */
    fun findMessages(
        provenanceUri: String,
        from: Instant,
        to: Instant,
        limit: Int,
        kinds: Collection<MessageKind> = MessageKind.entries,
    ): List<MessageLog> {
        if (kinds.isEmpty()) return emptyList()
        return repository
            .findByProvenanceUriAndKindInAndTimestampBetweenOrderByTimestampAsc(
                provenanceUri,
                kinds,
                from,
                to,
                PageRequest.of(0, limit),
            )
            .content
    }

    /**
     * Returns the latest [limit] messages for a provenance within a time window, in chronological
     * order: where [findMessages] keeps the oldest when the window holds more than [limit], this
     * keeps the newest, for "the last hour of the channel" and the like.
     */
    fun findLatestMessages(
        provenanceUri: String,
        from: Instant,
        to: Instant,
        limit: Int,
    ): List<MessageLog> {
        return repository
            .findByProvenanceUriAndTimestampBetweenOrderByTimestampDesc(
                provenanceUri,
                from,
                to,
                PageRequest.of(0, limit),
            )
            .content
            .reversed()
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

    /**
     * Messages in one provenance containing [text], ignoring case, and written by [sender] (a nick,
     * ignoring case), newest first: page [page] of [size]. Either may be left out, not both. The
     * text is matched as written: `%`, `_` and `\` in it are literal. Only lines of the given
     * [kinds] are found, every kind unless told otherwise.
     */
    fun searchMessages(
        provenanceUri: String,
        text: String?,
        sender: String?,
        page: Int,
        size: Int,
        kinds: Collection<MessageKind> = MessageKind.entries,
    ): Page<MessageLog> {
        require(text != null || sender != null) { "a search needs text, a sender, or both" }
        val pageable = PageRequest.of(page, size)
        if (kinds.isEmpty()) return Page.empty(pageable)
        val kindNames = kinds.map { it.name }
        val pattern = text?.let {
            "%" + it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        }
        return when {
            pattern == null -> repository.findBySender(provenanceUri, sender!!, kindNames, pageable)
            sender == null -> repository.searchContent(provenanceUri, pattern, kindNames, pageable)
            else ->
                repository.searchContentBySender(
                    provenanceUri,
                    sender,
                    pattern,
                    kindNames,
                    pageable,
                )
        }
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
        direct: Boolean,
        kind: MessageKind,
    ) {
        try {
            repository.save(
                MessageLog(
                    provenanceUri = provenanceUri,
                    direction = direction,
                    sender = sender,
                    content = content,
                    direct = direct,
                    kind = kind,
                )
            )
        } catch (e: Exception) {
            logger.error("Failed to log {} message for {}: {}", direction, provenanceUri, e.message)
        }
    }
}
