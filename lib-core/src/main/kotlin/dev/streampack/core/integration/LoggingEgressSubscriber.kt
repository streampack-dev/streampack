/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.integration

import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.MessageLogService
import org.springframework.stereotype.Component

/** Captures all outbound operation results to the protocol-agnostic message log */
@Component
class LoggingEgressSubscriber(private val messageLogService: MessageLogService) :
    EgressSubscriber() {

    /** Matches all protocols -- logging is unconditional */
    override fun matches(provenance: Provenance): Boolean = true

    override fun deliver(result: OperationResult, provenance: Provenance) {
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
