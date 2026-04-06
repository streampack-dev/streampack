/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.operation

import dev.streampack.core.extensions.compress
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.TranslatingOperation
import dev.streampack.factoid.model.FactoidAttributeType
import dev.streampack.factoid.model.FactoidSetRequest
import dev.streampack.factoid.service.FactoidService
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Handles addressed factoid assignment: "selector=value" or "selector.attribute=value" */
@Component
class SetFactoidOperation(private val factoidService: FactoidService) :
    TranslatingOperation<FactoidSetRequest>(FactoidSetRequest::class) {

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
                logger.debug("Factoid '{}' updated by {}", payload.selector, senderNick)
                OperationResult.Success("ok, $senderNick: updated ${payload.selector}.")
            }
            is FactoidService.SaveResult.Locked ->
                OperationResult.Error("Factoid '${payload.selector}' is locked.")
        }
    }

    companion object {
        private val DELIMITERS = listOf("=" to 1, " is " to 4)

        /** Parses "selector[.attribute]<= or is>value" into a FactoidSetRequest */
        fun parseInput(input: String): FactoidSetRequest? {
            val (matched, result) = tryAttributeSplit(input)
            if (matched) return result
            return trySimpleSplit(input)
        }

        /**
         * Scans for ".attribute=" or ".attribute is " patterns. Returns (true, request) on success,
         * (true, null) when an attribute pattern was found but the result is invalid, or (false,
         * null) when no attribute pattern was found at all.
         */
        private fun tryAttributeSplit(input: String): Pair<Boolean, FactoidSetRequest?> {
            data class Match(
                val position: Int,
                val attr: FactoidAttributeType,
                val fullPatternLength: Int,
            )

            val matches = mutableListOf<Match>()
            for ((name, attr) in FactoidAttributeType.knownAttributes) {
                if (!attr.mutable) continue
                for ((delim, _) in DELIMITERS) {
                    val pattern = ".$name$delim"
                    val idx = input.indexOf(pattern, ignoreCase = true)
                    if (idx > 0) {
                        matches.add(Match(idx, attr, pattern.length))
                    }
                }
            }

            if (matches.isEmpty()) return false to null

            val best = matches.minByOrNull { it.position }!!
            val selector = input.substring(0, best.position).compress()
            val value = input.substring(best.position + best.fullPatternLength).compress()
            if (selector.isBlank() || value.isBlank()) return true to null
            if ('=' in selector) return true to null
            return true to FactoidSetRequest(selector.lowercase(), best.attr, value)
        }

        /** Falls back to first "=" or " is " as a simple TEXT delimiter */
        private fun trySimpleSplit(input: String): FactoidSetRequest? {
            val candidates =
                DELIMITERS.mapNotNull { (delim, len) ->
                    val idx = input.indexOf(delim)
                    if (idx >= 1) idx to len else null
                }
            if (candidates.isEmpty()) return null

            val (splitIdx, delimLen) = candidates.minByOrNull { it.first }!!
            val selector = input.substring(0, splitIdx).compress()
            val value = input.substring(splitIdx + delimLen).compress()
            if (selector.isBlank() || value.isBlank()) return null

            return FactoidSetRequest(selector.lowercase(), FactoidAttributeType.TEXT, value)
        }
    }
}
