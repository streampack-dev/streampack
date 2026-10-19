/* Joseph B. Ottinger (C)2026 */
package dev.streampack.tell.operation

import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.TellTargetResolution
import dev.streampack.core.service.TellTargetResolver
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
 * A target in double quotes can have spaces in it (`tell "foo bar" hi`, `tell "mattermost://fn/foo
 * bar" hi`); a percent-encoded address (`mattermost://fn/foo%20bar`) needs no quotes. Then the
 * protocol's [TellTargetResolver], if it has one, reads the target: Mattermost finds a user's
 * direct-message channel from `@name`, and a channel's id from its name (#262).
 *
 * From the web console only a full URI will do: a console has no channels or people of its own for
 * a bare name to mean (#115). A malformed or unsupported address is refused, from anywhere, as is a
 * target that the protocol's resolver can't find.
 *
 * Uses provenance override on the result so delivery flows through the standard egress path.
 */
@Component
class TellOperation(private val resolvers: List<TellTargetResolver>) :
    TranslatingOperation<TellRequest>(TellRequest::class) {

    override val priority: Int = 50
    override val operationGroup: String = "tell"

    override fun translate(payload: String, message: Message<*>): TellRequest? {
        val trimmed = payload.trim()
        if (!trimmed.startsWith("tell ")) return null

        val rest = trimmed.removePrefix("tell ").trim()
        val provenance = message.headers[Provenance.HEADER] as? Provenance ?: return null
        val (rawTarget, messageText) =
            when (val split = split(rest)) {
                null -> return null
                Split.UnclosedQuote ->
                    return TellRequest(
                        provenance,
                        rest,
                        "The target's quote isn't closed: tell \"foo bar\" your message.",
                    )
                is Split.Parts -> split
            }

        val refusal = refusal(rawTarget, provenance)
        if (refusal != null) return TellRequest(provenance, messageText, refusal)
        return when (val resolved = resolve(resolveTarget(rawTarget, provenance))) {
            is TellTargetResolution.Resolved ->
                TellRequest(targetProvenance = resolved.provenance, message = messageText)
            is TellTargetResolution.Refused -> TellRequest(provenance, messageText, resolved.reason)
        }
    }

    /** How a `tell` line splits into its target and its message */
    private sealed interface Split {
        data class Parts(val target: String, val message: String) : Split

        data object UnclosedQuote : Split
    }

    /**
     * [rest] split into the target and the message: a quoted target runs to its closing quote, any
     * other to the first space. Null when there's no target or no message.
     */
    private fun split(rest: String): Split? {
        if (rest.startsWith('"')) {
            val close = rest.indexOf('"', 1)
            if (close < 0) return Split.UnclosedQuote
            val target = rest.substring(1, close).trim()
            val message = rest.substring(close + 1).trim()
            return if (target.isEmpty() || message.isEmpty()) null else Split.Parts(target, message)
        }
        val spaceIndex = rest.indexOf(' ')
        if (spaceIndex <= 0) return null
        val message = rest.substring(spaceIndex + 1).trim()
        return if (message.isEmpty()) null else Split.Parts(rest.substring(0, spaceIndex), message)
    }

    /** [target] as its protocol's resolver reads it, or as typed when the protocol has none */
    private fun resolve(target: Provenance): TellTargetResolution =
        resolvers.firstOrNull { it.protocol == target.protocol }?.resolve(target)
            ?: TellTargetResolution.Resolved(target)

    /** Why [rawTarget] can't be told anything from [source], or null if it can. */
    private fun refusal(rawTarget: String, source: Provenance): String? {
        if (rawTarget.contains("://")) {
            return if (runCatching { decode(rawTarget) }.isSuccess) null
            else "I can't deliver to $rawTarget: it isn't an address I know."
        }
        if (source.protocol == Protocol.WEBCONSOLE) {
            return "From the console, tell needs a full address, such as irc://libera/#java."
        }
        return null
    }

    override fun handle(payload: TellRequest, message: Message<*>): OperationOutcome {
        payload.refusal?.let {
            return OperationResult.Error(it)
        }
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
            return decode(rawTarget)
        }
        return Provenance(
            protocol = source.protocol,
            serviceId = source.serviceId,
            replyTo = rawTarget,
        )
    }

    /** A full address, where a quoted one's spaces are read as `%20` */
    private fun decode(address: String): Provenance = Provenance.decode(address.replace(" ", "%20"))
}
