/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.operation

import dev.streampack.core.extensions.compress
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.TranslatingOperation
import dev.streampack.factoid.model.FactoidAttributeType
import dev.streampack.factoid.model.FactoidForgetRequest
import dev.streampack.factoid.service.FactoidService
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Handles "forget selector" (whole factoid) and "forget selector.attribute" (single attribute) */
@Component
class ForgetFactoidOperation(private val factoidService: FactoidService) :
    TranslatingOperation<FactoidForgetRequest>(FactoidForgetRequest::class) {

    override val priority: Int = 70
    override val addressed: Boolean = true
    override val operationGroup: String = "factoid"

    override fun translate(payload: String, message: Message<*>): FactoidForgetRequest? {
        val compressed = payload.compress()
        if (!compressed.startsWith("forget ", ignoreCase = true)) return null
        val argument = compressed.substringAfter("forget ", "").trim()
        if (argument.isBlank()) return null

        val parsed = GetFactoidOperation.parseQuery(argument)
        return if (parsed.attribute == FactoidAttributeType.UNKNOWN) {
            FactoidForgetRequest(parsed.selector, null)
        } else {
            FactoidForgetRequest(parsed.selector, parsed.attribute)
        }
    }

    override fun handle(payload: FactoidForgetRequest, message: Message<*>): OperationOutcome {
        val provenance = message.headers[Provenance.HEADER] as? Provenance
        val senderNick =
            message.headers["nick"] as? String ?: provenance?.user?.username ?: "unknown"

        return if (payload.attribute != null) {
            handleAttributeForget(payload.selector, payload.attribute, senderNick)
        } else {
            handleFullForget(payload.selector, senderNick)
        }
    }

    /** Deletes a single attribute from a factoid */
    private fun handleAttributeForget(
        selector: String,
        type: FactoidAttributeType,
        senderNick: String,
    ): OperationOutcome {
        return when (val result = factoidService.deleteAttribute(selector, type)) {
            is FactoidService.DeleteResult.Ok -> {
                logger.debug("Attribute {} on '{}' forgotten by {}", type, selector, senderNick)
                OperationResult.Success("ok, forgot ${type.name.lowercase()} on $selector.")
            }
            is FactoidService.DeleteResult.Locked ->
                OperationResult.Error("Factoid '${result.selector}' is locked.")
            is FactoidService.DeleteResult.NotFound ->
                OperationResult.Error(
                    "No ${type.name.lowercase()} attribute found for '$selector'."
                )
        }
    }

    /** Deletes an entire factoid and all its attributes */
    private fun handleFullForget(selector: String, senderNick: String): OperationOutcome {
        val attributes = factoidService.findBySelector(selector)
        if (attributes.isEmpty()) {
            return OperationResult.Error("Factoid '$selector' not found.")
        }
        factoidService.deleteSelector(selector)
        logger.debug("Factoid '{}' forgotten by {}", selector, senderNick)
        return OperationResult.Success("ok, forgot $selector.")
    }
}
