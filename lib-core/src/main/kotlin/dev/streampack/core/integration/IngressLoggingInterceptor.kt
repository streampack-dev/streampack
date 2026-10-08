/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.integration

import dev.streampack.core.model.LoggingRequest
import dev.streampack.core.model.MessageKind
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.RedactionRule
import dev.streampack.core.service.ChannelControlService
import dev.streampack.core.service.DirectConversations
import dev.streampack.core.service.MessageLogService
import dev.streampack.core.service.Operation
import dev.streampack.core.service.SecretNotices
import dev.streampack.core.service.SecretScrubber
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.stereotype.Component

/**
 * Captures inbound messages flowing through the ingress channel to the message log, except for
 * channels whose controls say `logged=false`, which are never persisted. Direct conversations are
 * logged marked direct, so nothing reads them back. A [LoggingRequest] is logged with its kind (a
 * join, a quit...); anything else is a message.
 *
 * What's written is redacted twice: the operations' [RedactionRule]s take the secret arguments out
 * of commands that carry them, then the [SecretScrubber] takes out anything shaped like a
 * credential, wherever it appears (#148). The scrubbed text is only what's logged; the message
 * itself goes on to the operations unchanged.
 *
 * When a secret is scrubbed from a channel, its sender is told privately, through [SecretNotices].
 * Not from a direct conversation: nobody else saw it there, so there's nothing to revoke in a
 * hurry, and the row is scrubbed all the same.
 */
@Component
class IngressLoggingInterceptor(
    private val messageLogService: MessageLogService,
    private val channelControlService: ChannelControlService,
    private val directConversations: DirectConversations,
    operations: List<Operation>,
    private val secretScrubber: SecretScrubber,
    private val secretNotices: SecretNotices,
) : ChannelInterceptor {

    private val redactionRules: List<RedactionRule> = operations.flatMap { it.redactionRules }

    override fun preSend(message: Message<*>, channel: MessageChannel): Message<*> {
        val provenance = message.headers[Provenance.HEADER] as? Provenance ?: return message
        if (!channelControlService.isLogged(provenance)) return message
        val sender =
            message.headers["nick"] as? String
                ?: provenance.user?.displayName
                ?: provenance.user?.username
                ?: "unknown"
        val scrub = secretScrubber.scrub(redact(message.payload.toString(), redactionRules))
        val direct = directConversations.isDirect(provenance)
        val kind = (message.payload as? LoggingRequest)?.kind ?: MessageKind.MESSAGE
        messageLogService.logInbound(provenance.encode(), sender, scrub.text, direct, kind)
        val senderId = message.headers[Provenance.SENDER_ID] as? String
        if (scrub.scrubbed && !direct && senderId != null) {
            secretNotices.notify(provenance, senderId, scrub.kinds)
        }
        return message
    }

    companion object {
        private const val REDACTED = "[REDACTED]"

        /**
         * Applies redaction rules to replace secret tokens before logging. Matching tokenizes the
         * content the same way the command parsers do (any run of whitespace, leading whitespace
         * ignored, case-insensitive literals), so `mattermost connect …` and `mattermost connect …`
         * are the same command here as they are to the parser.
         */
        fun redact(content: String, rules: List<RedactionRule>): String {
            val tokens = content.trim().split(WHITESPACE).filter { it.isNotEmpty() }
            for (rule in rules) {
                val prefix = rule.prefix.trim().split(WHITESPACE).filter { it.isNotEmpty() }
                if (tokens.size < prefix.size) continue
                val matches =
                    prefix.indices.all { i -> tokens[i].equals(prefix[i], ignoreCase = true) }
                if (!matches) continue
                val redacted = tokens.toMutableList()
                for (pos in rule.positions) {
                    if (pos < redacted.size) redacted[pos] = REDACTED
                }
                return redacted.joinToString(" ")
            }
            return content
        }

        private val WHITESPACE = Regex("\\s+")
    }
}
