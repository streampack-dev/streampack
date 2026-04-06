/* Joseph B. Ottinger (C)2026 */
package dev.streampack.tell.operation

import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.TranslatingOperation
import dev.streampack.tell.model.TellRequest
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/**
 * Delivers a message to a target destination identified by name, channel, or full URI.
 *
 * Target resolution inherits context from the source provenance:
 * - Just a name ("blue") resolves to a private message on the same protocol/service
 * - A channel name ("#java") resolves to a channel on the same protocol/service
 * - A full URI ("irc://othernet/#java") uses the URI as-is
 *
 * Uses provenance override on the result so delivery flows through the standard egress path.
 */
@Component
class TellOperation : TranslatingOperation<TellRequest>(TellRequest::class) {

    override val priority: Int = 50
    override val operationGroup: String = "tell"

    override fun translate(payload: String, message: Message<*>): TellRequest? {
        val trimmed = payload.trim()
        if (!trimmed.startsWith("tell ")) return null

        val rest = trimmed.removePrefix("tell ").trim()
        val spaceIndex = rest.indexOf(' ')
        if (spaceIndex <= 0) return null

        val rawTarget = rest.substring(0, spaceIndex)
        val messageText = rest.substring(spaceIndex + 1).trim()
        if (messageText.isBlank()) return null

        val provenance = message.headers[Provenance.HEADER] as? Provenance ?: return null
        val targetProvenance = resolveTarget(rawTarget, provenance)
        return TellRequest(targetProvenance = targetProvenance, message = messageText)
    }

    override fun handle(payload: TellRequest, message: Message<*>): OperationOutcome {
        val sourceProvenance = message.headers[Provenance.HEADER] as? Provenance
        val senderNick =
            message.headers["nick"] as? String ?: sourceProvenance?.user?.username ?: "someone"

        val attributed = "<$senderNick> ${payload.message}"
        return OperationResult.Success(attributed, provenance = payload.targetProvenance)
    }

    /**
     * Resolves a target string against the source provenance. Full URIs are used directly;
     * otherwise the target inherits protocol and serviceId from the source.
     */
    private fun resolveTarget(rawTarget: String, source: Provenance): Provenance {
        if (rawTarget.contains("://")) {
            return Provenance.decode(rawTarget)
        }
        return Provenance(
            protocol = source.protocol,
            serviceId = source.serviceId,
            replyTo = rawTarget,
        )
    }
}
