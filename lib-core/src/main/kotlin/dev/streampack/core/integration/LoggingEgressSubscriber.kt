/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.integration

import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.ChannelControlService
import dev.streampack.core.service.MessageLogService
import org.springframework.stereotype.Component

/**
 * Captures outbound operation results to the protocol-agnostic message log, except for channels
 * whose controls say `logged=false`.
 */
@Component
class LoggingEgressSubscriber(
    private val messageLogService: MessageLogService,
    private val channelControlService: ChannelControlService,
) : EgressSubscriber() {

    /** Matches all protocols; the per-channel `logged` flag is checked at delivery */
    override fun matches(provenance: Provenance): Boolean = true

    override fun deliver(result: OperationResult, provenance: Provenance) {
        if (!channelControlService.isLogged(provenance)) return
        val sender = provenance.metadata[Provenance.BOT_NICK] as? String ?: "bot"
        when (result) {
            is OperationResult.Success ->
                messageLogService.logOutbound(
                    provenance.encode(),
                    sender,
                    result.payload.toString(),
                )
            is OperationResult.Error ->
                messageLogService.logOutbound(
                    provenance.encode(),
                    sender,
                    "Error: ${result.message}",
                )
            is OperationResult.NotHandled -> {}
        }
    }
}
