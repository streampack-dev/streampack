/* Joseph B. Ottinger (C)2026 */
package dev.streampack.markov.operation

import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.MessageLogService
import dev.streampack.core.service.TranslatingOperation
import dev.streampack.markov.model.MarkovRequest
import dev.streampack.markov.service.MarkovChainService
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Generates a sentence in the style of a given user from their message history */
@Component
class BeOperation(
    private val messageLogService: MessageLogService,
    private val markovChainService: MarkovChainService,
) : TranslatingOperation<MarkovRequest>(MarkovRequest::class) {

    override val priority: Int = 50
    override val addressed: Boolean = true
    override val operationGroup: String = "markov"

    override fun translate(payload: String, message: Message<*>): MarkovRequest? {
        val trimmed = payload.trim()
        if (!trimmed.startsWith("be ")) return null
        val username = trimmed.removePrefix("be ").trim()
        if (username.isBlank()) return null
        return MarkovRequest(username)
    }

    override fun handle(payload: MarkovRequest, message: Message<*>): OperationOutcome {
        val provenance =
            message.headers[Provenance.HEADER] as? Provenance
                ?: return OperationResult.Error("No provenance available")

        val protocolPrefix = "${provenance.protocol.name.lowercase()}://"

        val messages =
            messageLogService
                .findRecentMessagesBySender(payload.username, protocolPrefix, CORPUS_SIZE)
                .map { it.content }

        if (messages.isEmpty()) {
            return OperationResult.Success("No message history found for ${payload.username}.")
        }

        val generated = markovChainService.generate(messages)
        if (generated == null) {
            return OperationResult.Success("Not enough data to channel ${payload.username}.")
        }

        logger.debug(
            "Generated Markov sentence for {} from {} messages",
            payload.username,
            messages.size,
        )
        return OperationResult.Success("* channeling ${payload.username}: $generated")
    }

    companion object {
        private const val CORPUS_SIZE = 1000
    }
}
