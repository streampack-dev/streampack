/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.integration

import com.enigmastation.streampack.core.model.Provenance
import com.enigmastation.streampack.core.model.RedactionRule
import com.enigmastation.streampack.core.service.MessageLogService
import com.enigmastation.streampack.core.service.Operation
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.stereotype.Component

/** Captures all inbound messages flowing through the ingress channel to the message log */
@Component
class IngressLoggingInterceptor(
    private val messageLogService: MessageLogService,
    operations: List<Operation>,
) : ChannelInterceptor {

    private val redactionRules: List<RedactionRule> = operations.flatMap { it.redactionRules }

    override fun preSend(message: Message<*>, channel: MessageChannel): Message<*> {
        val provenance = message.headers[Provenance.HEADER] as? Provenance ?: return message
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

        /** Applies redaction rules to replace secret tokens before logging */
        fun redact(content: String, rules: List<RedactionRule>): String {
            val lower = content.lowercase()
            for (rule in rules) {
                if (lower.startsWith(rule.prefix.lowercase())) {
                    val tokens = content.split("\\s+".toRegex()).toMutableList()
                    for (pos in rule.positions) {
                        if (pos < tokens.size) {
                            tokens[pos] = REDACTED
                        }
                    }
                    return tokens.joinToString(" ")
                }
            }
            return content
        }
    }
}
