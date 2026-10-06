/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.integration

import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.ChannelControlService
import dev.streampack.core.service.DirectConversations
import dev.streampack.core.service.MessageLogService
import org.springframework.stereotype.Component

/**
 * Captures outbound operation results to the protocol-agnostic message log, except for channels
 * whose controls say `logged=false`. Replies in direct conversations are logged marked direct.
 *
 * Web console output (#115) is logged as its outcome only, never its text: an admin's console can
 * show what nothing else should keep (and outbound text has no redaction, as ingress has), so the
 * log says that a result went out, and what kind, but not what it said. Log-derived context (Ask's
 * recent-conversation context, say) sees the commands, redacted as ever, but not their answers.
 */
@Component
class LoggingEgressSubscriber(
    private val messageLogService: MessageLogService,
    private val channelControlService: ChannelControlService,
    private val directConversations: DirectConversations,
) : EgressSubscriber() {

    /** Matches all protocols; the per-channel `logged` flag is checked at delivery */
    override fun matches(provenance: Provenance): Boolean = true

    override fun deliver(result: OperationResult, provenance: Provenance) {
        if (!channelControlService.isLogged(provenance)) return
        val sender = provenance.metadata[Provenance.BOT_NICK] as? String ?: "bot"
        val direct = directConversations.isDirect(provenance)
        if (provenance.protocol == Protocol.WEBCONSOLE) {
            val outcome =
                when (result) {
                    is OperationResult.Success -> "success"
                    is OperationResult.Error -> "error"
                    is OperationResult.NotHandled -> return
                }
            messageLogService.logOutbound(
                provenance.encode(),
                sender,
                "[web console: $outcome]",
                direct,
            )
            return
        }
        when (result) {
            is OperationResult.Success ->
                messageLogService.logOutbound(
                    provenance.encode(),
                    sender,
                    result.payload.toString(),
                    direct,
                )
            is OperationResult.Error ->
                messageLogService.logOutbound(
                    provenance.encode(),
                    sender,
                    "Error: ${result.message}",
                    direct,
                )
            is OperationResult.NotHandled -> {}
        }
    }
}
