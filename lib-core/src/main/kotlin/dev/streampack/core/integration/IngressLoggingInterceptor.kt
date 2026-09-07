/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.integration

import dev.streampack.core.model.Provenance
import dev.streampack.core.model.RedactionRule
import dev.streampack.core.service.ChannelControlService
import dev.streampack.core.service.MessageLogService
import dev.streampack.core.service.Operation
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.stereotype.Component

/**
 * Captures inbound messages flowing through the ingress channel to the message log, except for
 * channels whose controls say `logged=false`, which are never persisted.
 */
@Component
class IngressLoggingInterceptor(
    private val messageLogService: MessageLogService,
    private val channelControlService: ChannelControlService,
    operations: List<Operation>,
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
        val content = redact(message.payload.toString(), redactionRules)
        messageLogService.logInbound(provenance.encode(), sender, content)
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
