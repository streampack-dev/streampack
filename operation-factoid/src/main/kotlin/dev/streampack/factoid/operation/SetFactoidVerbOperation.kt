/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.operation

import dev.streampack.core.extensions.compress
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.TranslatingOperation
import dev.streampack.factoid.model.FactoidAttributeType
import dev.streampack.factoid.model.FactoidVerbSetRequest
import dev.streampack.factoid.service.FactoidService
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Handles "factoid set selector.attribute value" - always requires explicit attribute */
@Component
class SetFactoidVerbOperation(private val factoidService: FactoidService) :
    TranslatingOperation<FactoidVerbSetRequest>(FactoidVerbSetRequest::class) {

    override val priority: Int = 70
    override val addressed: Boolean = true
    override val operationGroup: String = "factoid"

    override fun translate(payload: String, message: Message<*>): FactoidVerbSetRequest? {
        val compressed = payload.compress()
        if (!compressed.startsWith("factoid set ", ignoreCase = true)) return null
        val argument = compressed.substringAfter("factoid set ", "").trim()
        if (argument.isBlank()) return null
        return parseSetArgument(argument)
    }

    override fun handle(payload: FactoidVerbSetRequest, message: Message<*>): OperationOutcome {
        val provenance = message.headers[Provenance.HEADER] as? Provenance
        val senderNick =
            message.headers["nick"] as? String ?: provenance?.user?.username ?: "unknown"

        return when (
            val result =
                factoidService.save(payload.selector, payload.attribute, payload.value, senderNick)
        ) {
            is FactoidService.SaveResult.Ok -> {
                logger.debug("Factoid '{}' updated by {}", payload.selector, senderNick)
                OperationResult.Success("ok, $senderNick: updated ${payload.selector}.")
            }
            is FactoidService.SaveResult.Locked ->
                OperationResult.Error("Factoid '${result.selector}' is locked.")
        }
    }

    companion object {
        /** Parses "selector.attribute value" into a FactoidVerbSetRequest */
        fun parseSetArgument(input: String): FactoidVerbSetRequest? {
            // Scan for .knownAttribute followed by a space
            data class Match(
                val position: Int,
                val attr: FactoidAttributeType,
                val patternLength: Int,
            )

            val matches = mutableListOf<Match>()
            for ((name, attr) in FactoidAttributeType.knownAttributes) {
                if (!attr.mutable) continue
                val pattern = ".$name "
                val idx = input.indexOf(pattern, ignoreCase = true)
                if (idx > 0) {
                    matches.add(Match(idx, attr, pattern.length))
                }
            }

            if (matches.isEmpty()) return null

            val best = matches.minByOrNull { it.position }!!
            val selector = input.substring(0, best.position).compress()
            val value = input.substring(best.position + best.patternLength).compress()
            if (selector.isBlank() || value.isBlank()) return null
            return FactoidVerbSetRequest(selector.lowercase(), best.attr, value)
        }
    }
}
