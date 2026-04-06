/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.integration

import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import org.springframework.messaging.Message
import org.springframework.messaging.MessageHandler
import org.springframework.messaging.MessageHeaders

/**
 * Base class for services that watch the egress channel for operation results.
 *
 * Subclasses implement [matches] to declare which messages they claim (typically by checking the
 * provenance protocol) and [deliver] to handle the matched result. The infrastructure subscribes
 * all EgressSubscriber beans to the egress channel automatically.
 *
 * The subscriber is intentionally thin -- it filters and dispatches. All routing decisions (muting,
 * channel selection, network lookup) belong in the service, not here.
 *
 * Reference tokens (`{{ref:name}}`) in operation output are rendered to protocol-specific form
 * before delivery. Override [resolveSignalCharacter] to provide the signal prefix for your
 * protocol.
 */
abstract class EgressSubscriber : MessageHandler {

    /** Return true if this subscriber should receive messages with the given provenance */
    abstract fun matches(provenance: Provenance): Boolean

    /** Handle a matched operation result. Called only when [matches] returned true. */
    abstract fun deliver(result: OperationResult, provenance: Provenance)

    /** Optional access to the raw egress headers for subscribers that need them. */
    protected open fun deliver(
        result: OperationResult,
        provenance: Provenance,
        headers: MessageHeaders,
    ) {
        deliver(result, provenance)
    }

    /** Override to provide the signal character for this provenance's protocol/service */
    protected open fun resolveSignalCharacter(provenance: Provenance): String = ""

    final override fun handleMessage(message: Message<*>) {
        val provenance = message.headers[Provenance.HEADER] as? Provenance ?: return
        val result = message.payload as? OperationResult ?: return
        if (matches(provenance)) {
            deliver(renderReferenceTokens(result, provenance), provenance, message.headers)
        }
    }

    /** Replaces {{ref:name}} tokens with signal-prefixed names for the target protocol */
    private fun renderReferenceTokens(
        result: OperationResult,
        provenance: Provenance,
    ): OperationResult {
        val text =
            when (result) {
                is OperationResult.Success -> result.payload.toString()
                is OperationResult.Error -> result.message
                is OperationResult.NotHandled -> return result
            }
        if (!text.contains(REF_TOKEN_PREFIX)) return result
        val signal = resolveSignalCharacter(provenance)
        val rendered = text.replace(REF_TOKEN_REGEX) { match -> "$signal${match.groupValues[1]}" }
        if (rendered == text) return result
        return when (result) {
            is OperationResult.Success -> result.copy(payload = rendered)
            is OperationResult.Error -> result.copy(message = rendered)
            is OperationResult.NotHandled -> result
        }
    }

    companion object {
        const val REF_TOKEN_PREFIX = "{{ref:"
        private val REF_TOKEN_REGEX = Regex("\\{\\{ref:([^}]+)}}")
    }
}
