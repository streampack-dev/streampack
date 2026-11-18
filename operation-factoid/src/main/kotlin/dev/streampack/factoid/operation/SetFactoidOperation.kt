/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.operation

import dev.streampack.core.extensions.compress
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.integration.forFollowUp
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.TranslatingOperation
import dev.streampack.factoid.model.FactoidAttributeType
import dev.streampack.factoid.model.FactoidSetRequest
import dev.streampack.factoid.model.FactoidUpdatedEvent
import dev.streampack.factoid.service.FactoidService
import org.springframework.messaging.Message
import org.springframework.messaging.support.MessageBuilder
import org.springframework.stereotype.Component

/** Handles addressed factoid assignment: "selector=value" or "selector.attribute=value" */
@Component
class SetFactoidOperation(
    private val factoidService: FactoidService,
    private val eventGateway: EventGateway,
) : TranslatingOperation<FactoidSetRequest>(FactoidSetRequest::class) {

    override val priority: Int = 75
    override val addressed: Boolean = true
    override val operationGroup: String = "factoid"

    override fun translate(payload: String, message: Message<*>): FactoidSetRequest? {
        return parseInput(payload)
    }

    override fun handle(payload: FactoidSetRequest, message: Message<*>): OperationOutcome? {
        val provenance = message.headers[Provenance.HEADER] as? Provenance
        val senderNick =
            message.headers["nick"] as? String ?: provenance?.user?.username ?: "unknown"

        val result =
            factoidService.save(payload.selector, payload.attribute, payload.value, senderNick)
        return when (result) {
            is FactoidService.SaveResult.Ok -> {
                eventGateway.send(
                    MessageBuilder.withPayload(FactoidUpdatedEvent(payload.selector))
                        .copyHeadersIfAbsent(message.headers.forFollowUp())
                        .build(),
                )
                logger.debug("Factoid '{}' updated by {}", payload.selector, senderNick)
                OperationResult.Success("ok, $senderNick: updated ${payload.selector}.")
            }
            is FactoidService.SaveResult.Locked ->
                OperationResult.Error("Factoid '${payload.selector}' is locked.")
        }
    }

    companion object {
        /** `=` with any whitespace around it, or `is` with at least one whitespace each side. */
        private const val DELIMITER = """\s*=\s*|\s+is\s+"""

        private val SIMPLE_SPLIT = Regex(DELIMITER)

        /** `.attribute` followed by a delimiter, for every attribute a user may set. */
        private val ATTRIBUTE_SPLIT =
            Regex(
                "\\.(" +
                    FactoidAttributeType.knownAttributes
                        .filterValues { it.mutable }
                        .keys
                        .joinToString("|") { Regex.escape(it) } +
                    ")(?:$DELIMITER)",
                RegexOption.IGNORE_CASE,
            )

        /**
         * Parses "selector[.attribute]<= or is>value" into a FactoidSetRequest. The amount of
         * whitespace around `=` and `is` doesn't matter: `foo.see = bar` sets `see` on `foo`.
         *
         * @param input the message, without the bot's prefix.
         * @return the request, or null when the input has no delimiter, a blank selector or a blank
         *   value.
         */
        fun parseInput(input: String): FactoidSetRequest? {
            val (matched, result) = tryAttributeSplit(input)
            if (matched) return result
            return trySimpleSplit(input)
        }

        /**
         * Finds the first ".attribute=" or ".attribute is ". Returns (true, request) on success,
         * (true, null) when an attribute pattern was found but the result is invalid, or (false,
         * null) when no attribute pattern was found at all.
         */
        private fun tryAttributeSplit(input: String): Pair<Boolean, FactoidSetRequest?> {
            val match = ATTRIBUTE_SPLIT.find(input) ?: return false to null
            val attr = FactoidAttributeType.knownAttributes[match.groupValues[1].lowercase()]!!
            val selector = input.substring(0, match.range.first).compress()
            val value = input.substring(match.range.last + 1).compress()
            if (selector.isBlank() || value.isBlank()) return true to null
            if ('=' in selector) return true to null
            return true to FactoidSetRequest(selector.lowercase(), attr, value)
        }

        /** Falls back to the first "=" or " is " as a simple TEXT delimiter */
        private fun trySimpleSplit(input: String): FactoidSetRequest? {
            val match = SIMPLE_SPLIT.find(input) ?: return null
            val selector = input.substring(0, match.range.first).compress()
            val value = input.substring(match.range.last + 1).compress()
            if (selector.isBlank() || value.isBlank()) return null

            return FactoidSetRequest(selector.lowercase(), FactoidAttributeType.TEXT, value)
        }
    }
}
