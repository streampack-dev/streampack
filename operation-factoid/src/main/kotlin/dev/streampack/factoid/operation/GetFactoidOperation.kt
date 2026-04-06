/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.operation

import dev.streampack.core.config.StreampackProperties
import dev.streampack.core.extensions.compress
import dev.streampack.core.extensions.joinToStringWithAnd
import dev.streampack.core.extensions.pluralize
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.service.TranslatingOperation
import dev.streampack.factoid.entity.FactoidAttribute
import dev.streampack.factoid.model.FactoidAttributeType
import dev.streampack.factoid.model.FactoidQueryRequest
import dev.streampack.factoid.service.FactoidService
import dev.streampack.factoid.service.FactoidTextRenderer
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Catch-all factoid lookup; returns null when no factoid matches so the chain continues */
@Component
class GetFactoidOperation(
    private val factoidService: FactoidService,
    private val factoidTextRenderer: FactoidTextRenderer,
    private val streampackProperties: StreampackProperties,
) : TranslatingOperation<FactoidQueryRequest>(FactoidQueryRequest::class) {

    override val priority: Int = 90
    override val addressed: Boolean = true
    override val operationGroup: String = "factoid"

    override fun translate(payload: String, message: Message<*>): FactoidQueryRequest? {
        return parseQuery(payload)
    }

    override fun handle(payload: FactoidQueryRequest, message: Message<*>): OperationOutcome? {
        return try {
            val searchResult =
                factoidService.findSelectorWithArguments(payload.selector)
                    ?: return missingSelectorOutcome(payload)
            val (selector, argument) = searchResult
            val attributes = factoidService.findBySelector(selector)
            if (attributes.isEmpty()) return missingSelectorOutcome(payload)

            // Follow .see redirects for default queries
            if (payload.attribute == FactoidAttributeType.UNKNOWN) {
                val seeAttr =
                    attributes.firstOrNull { it.attributeType == FactoidAttributeType.SEE }
                if (seeAttr != null) {
                    return resolveWithHops(
                        seeAttr.attributeValue ?: return null,
                        argument,
                        streampackProperties.maxHops - 1,
                    )
                }
            }

            // Record access for read queries, not mutations or meta-queries
            if (
                payload.attribute !in
                    setOf(
                        FactoidAttributeType.FORGET,
                        FactoidAttributeType.LOCK,
                        FactoidAttributeType.UNLOCK,
                        FactoidAttributeType.STATS,
                    )
            ) {
                factoidService.recordAccess(selector)
            }

            when (payload.attribute) {
                FactoidAttributeType.FORGET -> handleForget(selector, message)
                FactoidAttributeType.UNKNOWN -> handleSummary(selector, attributes, argument)
                FactoidAttributeType.INFO -> handleInfo(selector, attributes)
                FactoidAttributeType.LITERAL -> handleLiteral(selector, attributes)
                FactoidAttributeType.LOCK -> handleLock(selector, true, message)
                FactoidAttributeType.UNLOCK -> handleLock(selector, false, message)
                FactoidAttributeType.STATS -> handleStats(selector)
                else -> handleSpecificAttribute(selector, payload.attribute, attributes, argument)
            }
        } catch (e: NotEnoughArgumentsException) {
            OperationResult.Error(e.message!!)
        } catch (_: TooManyArgumentsException) {
            null
        }
    }

    private fun missingSelectorOutcome(payload: FactoidQueryRequest): OperationOutcome? {
        return when (payload.attribute) {
            FactoidAttributeType.INFO,
            FactoidAttributeType.LITERAL,
            FactoidAttributeType.LOCK,
            FactoidAttributeType.UNLOCK,
            FactoidAttributeType.STATS ->
                OperationResult.Error("Factoid '${payload.selector}' not found.")
            else -> null
        }
    }

    /** Follows .see redirect chains with hop counting to prevent cycles */
    private fun resolveWithHops(
        selector: String,
        argument: String,
        hopsRemaining: Int,
    ): OperationOutcome? {
        if (hopsRemaining <= 0) return null
        val result = factoidService.findSelectorWithArguments(selector) ?: return null
        val (resolved, resolvedArg) = result
        val attrs = factoidService.findBySelector(resolved)
        if (attrs.isEmpty()) return null

        val seeAttr = attrs.firstOrNull { it.attributeType == FactoidAttributeType.SEE }
        if (seeAttr != null) {
            return resolveWithHops(
                seeAttr.attributeValue ?: return null,
                argument.ifEmpty { resolvedArg },
                hopsRemaining - 1,
            )
        }
        return handleSummary(resolved, attrs, argument.ifEmpty { resolvedArg })
    }

    /** Deletes the factoid and confirms */
    private fun handleForget(selector: String, message: Message<*>): OperationOutcome? {
        val provenance = message.headers[Provenance.HEADER] as? Provenance
        val senderNick =
            message.headers["nick"] as? String ?: provenance?.user?.username ?: "unknown"
        factoidService.deleteSelector(selector)
        logger.debug("Factoid '{}' forgotten by {}", selector, senderNick)
        return OperationResult.Success("ok, forgot $selector.")
    }

    /** Renders all includeInSummary attributes in ordinal order */
    private fun handleSummary(
        selector: String,
        attributes: List<FactoidAttribute>,
        argument: String,
    ): OperationOutcome {
        val summary =
            attributes.summarize(
                selector,
                argument,
                factoidTextRenderer,
                streampackProperties.maxHops,
                factoidService,
            )
        return OperationResult.Success(summary)
    }

    /** Lists available attribute types and last modification info */
    private fun handleInfo(selector: String, attributes: List<FactoidAttribute>): OperationOutcome {
        val lastAttribute = attributes.sortedByDescending { it.updatedAt }.first()
        val attributeList = attributes.buildAvailableAttributeList()
        val response = buildString {
            append("The factoid for $selector has the following ")
            append("attribute".pluralize(attributes))
            append(": $attributeList")
            append(", and was last modified at ${lastAttribute.updatedAt}")
            if (lastAttribute.updatedBy != null) {
                append(" by ${lastAttribute.updatedBy}")
            }
        }
        return OperationResult.Success(response)
    }

    /** Returns access statistics for a factoid */
    private fun handleStats(selector: String): OperationOutcome {
        val factoid = factoidService.findFactoid(selector)
        return if (factoid != null && factoid.accessCount > 0) {
            OperationResult.Success(
                "$selector has been accessed ${factoid.accessCount} time${if (factoid.accessCount != 1L) "s" else ""}, last accessed at ${factoid.lastAccessedAt}."
            )
        } else {
            OperationResult.Success("$selector has never been accessed.")
        }
    }

    /** Returns the raw TEXT value with no rendering, interpolation, or selection resolution */
    private fun handleLiteral(
        selector: String,
        attributes: List<FactoidAttribute>,
    ): OperationOutcome? {
        val textAttr =
            attributes.firstOrNull { it.attributeType == FactoidAttributeType.TEXT } ?: return null
        return OperationResult.Success(textAttr.attributeValue ?: return null)
    }

    /** Admin-only lock/unlock toggle */
    private fun handleLock(
        selector: String,
        locked: Boolean,
        message: Message<*>,
    ): OperationOutcome {
        val provenance = message.headers[Provenance.HEADER] as? Provenance
        val role = provenance?.user?.role
        if (role != Role.ADMIN && role != Role.SUPER_ADMIN) {
            return OperationResult.Error("Lock/unlock requires admin privileges.")
        }
        val action = if (locked) "locked" else "unlocked"
        return if (factoidService.setLocked(selector, locked)) {
            OperationResult.Success("ok, $selector is now $action.")
        } else {
            OperationResult.Error("Factoid '$selector' not found.")
        }
    }

    /** Dispatches to the specific attribute type's renderer */
    private fun handleSpecificAttribute(
        selector: String,
        type: FactoidAttributeType,
        attributes: List<FactoidAttribute>,
        argument: String,
    ): OperationOutcome? {
        val attribute = attributes.firstOrNull { it.attributeType == type } ?: return null
        if (attribute.attributeValue.isNullOrEmpty()) return null

        val value =
            when (type) {
                FactoidAttributeType.TEXT -> renderTextAttribute(selector, attribute, argument)
                FactoidAttributeType.SEEALSO -> renderSeeAlso(selector, attribute)
                else -> attribute.attributeValue
            }
        return OperationResult.Success(type.render(selector, value))
    }

    /** Renders TEXT with <reply> prefix handling, $1 interpolation, and selection resolution */
    private fun renderTextAttribute(
        selector: String,
        attribute: FactoidAttribute,
        argument: String,
    ): String {
        val value = attribute.attributeValue!!
        if (!hasPlaceholder(value) && argument.isNotEmpty()) {
            throw TooManyArgumentsException()
        }
        val substituted = replaceParameters(selector, value, argument)
        return factoidTextRenderer.resolveSelections(substituted, streampackProperties.maxHops)
    }

    /** Decorates see-also values with ~ prefix for existing factoids */
    private fun renderSeeAlso(selector: String, attribute: FactoidAttribute): String {
        return decorateSeeAlso(selector, attribute.attributeValue!!, factoidService)
    }

    companion object {
        /** Parses a query string into a FactoidQueryRequest */
        fun parseQuery(input: String): FactoidQueryRequest {
            val compressed = input.compress()
            val lastDotIndex = compressed.lastIndexOf('.')
            return if (lastDotIndex != -1) {
                val potentialAttribute = compressed.substring(lastDotIndex + 1).trim().lowercase()
                if (potentialAttribute in FactoidAttributeType.knownAttributes) {
                    FactoidQueryRequest(
                        compressed.substring(0, lastDotIndex).trim(),
                        FactoidAttributeType.knownAttributes[potentialAttribute]!!,
                    )
                } else {
                    FactoidQueryRequest(compressed, FactoidAttributeType.UNKNOWN)
                }
            } else {
                FactoidQueryRequest(compressed, FactoidAttributeType.UNKNOWN)
            }
        }

        fun hasPlaceholder(value: String): Boolean = "\$1" in value

        fun replaceParameters(selector: String, value: String, argument: String): String {
            if (hasPlaceholder(value) && argument.isEmpty()) {
                throw NotEnoughArgumentsException(
                    "$selector: Not enough arguments to replace placeholder."
                )
            }
            return value.replace("\$1", argument)
        }
    }
}

/** Summarizes all includeInSummary attributes in enum ordinal order */
fun List<FactoidAttribute>.summarize(
    selector: String,
    argument: String,
    textRenderer: FactoidTextRenderer? = null,
    maxHops: Int = 3,
    factoidService: FactoidService? = null,
): String {
    return this.filter { it.attributeType.includeInSummary }
        .sortedBy { it.attributeType.ordinal }
        .filter { !it.attributeValue.isNullOrEmpty() }
        .mapNotNull { attr ->
            val value =
                when (attr.attributeType) {
                    FactoidAttributeType.TEXT -> {
                        if (
                            !GetFactoidOperation.hasPlaceholder(attr.attributeValue!!) &&
                                argument.isNotEmpty()
                        ) {
                            throw TooManyArgumentsException()
                        }
                        val substituted =
                            GetFactoidOperation.replaceParameters(
                                selector,
                                attr.attributeValue?.trim() ?: "",
                                argument,
                            )
                        textRenderer?.resolveSelections(substituted, maxHops) ?: substituted
                    }
                    FactoidAttributeType.SEEALSO ->
                        decorateSeeAlso(selector, attr.attributeValue!!, factoidService)
                    else -> attr.attributeValue
                }
            val rendered = attr.attributeType.render(selector, value)
            rendered.ifEmpty { null }
        }
        .joinToString(" ")
        .compress()
}

/** Decorates see-also entries with reference tokens when they are known factoids */
private fun decorateSeeAlso(
    selector: String,
    value: String,
    factoidService: FactoidService?,
): String {
    if (factoidService == null) return value
    return value
        .split(",")
        .map { it.trim() }
        .filterNot { it.equals(selector, ignoreCase = true) }
        .map {
            val clean = it.removePrefix("~")
            if (factoidService.findBySelector(clean).isNotEmpty()) {
                "{{ref:$clean}}"
            } else {
                clean
            }
        }
        .joinToString(",")
}

/** Builds a human-readable list of available attribute types */
fun List<FactoidAttribute>.buildAvailableAttributeList(): String {
    return this.filter { !it.attributeValue.isNullOrEmpty() }
        .map { attr ->
            val values =
                when (attr.attributeType) {
                    FactoidAttributeType.URLS,
                    FactoidAttributeType.TAGS,
                    FactoidAttributeType.LANGUAGES -> attr.attributeValue!!.split(",")
                    else -> listOf(attr.attributeValue)
                }
            attr.attributeType.toString().lowercase().pluralize(values)
        }
        .joinToStringWithAnd()
}

class NotEnoughArgumentsException(message: String) : Exception(message)

class TooManyArgumentsException : Exception()
