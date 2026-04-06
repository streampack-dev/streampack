/* Joseph B. Ottinger (C)2026 */
package dev.streampack.specs.operation

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.parser.CommandArgSpec
import dev.streampack.core.parser.CommandMatchResult
import dev.streampack.core.parser.CommandPattern
import dev.streampack.core.parser.CommandPatternMatcher
import dev.streampack.core.parser.PositiveIntArgType
import dev.streampack.core.service.TranslatingOperation
import dev.streampack.specs.model.SpecRequest
import dev.streampack.specs.model.SpecType
import dev.streampack.specs.service.SpecLookupService
import org.springframework.messaging.Message
import org.springframework.messaging.support.MessageBuilder
import org.springframework.stereotype.Component

/**
 * Looks up RFC, JEP, and JSR specifications by number.
 *
 * Acts as a factoid cache-miss handler: runs after the factoid get operation (priority 90) so that
 * known specs are served from the factoid store. On first lookup, fetches the spec title from the
 * source website, caches it as a factoid via EventGateway, and returns the result.
 */
@Component
class SpecsOperation(
    private val lookupService: SpecLookupService,
    private val eventGateway: EventGateway,
) : TranslatingOperation<SpecRequest>(SpecRequest::class) {

    override val priority: Int = 95
    override val addressed: Boolean = true
    override val operationGroup: String = "specs"

    private val compactPattern = Regex("^(rfc|jep|jsr|pep)(\\d+)$", RegexOption.IGNORE_CASE)

    override fun translate(payload: String, message: Message<*>): SpecRequest? {
        val trimmed = payload.trim()
        compactPattern.matchEntire(trimmed)?.let { match ->
            val identifier = match.groupValues[2].toIntOrNull() ?: return null
            return toRequest(match.groupValues[1], identifier)
        }

        return when (val match = matcher.match(trimmed)) {
            is CommandMatchResult.Match -> {
                val typeToken = match.patternName
                val identifier = match.captures["identifier"] as Int
                toRequest(typeToken, identifier)
            }
            else -> null
        }
    }

    override fun handle(payload: SpecRequest, message: Message<*>): OperationOutcome? {
        val title = lookupService.lookup(payload) ?: return null

        val description = "$title (${payload.url})"

        seedFactoid(payload.selector, title)
        seedFactoid("${payload.selector}.url", payload.url)

        return OperationResult.Success("${payload.selector}: $description")
    }

    /** Create a factoid so future lookups hit the factoid cache instead of the web */
    private fun seedFactoid(selector: String, value: String) {
        try {
            val factoidText = "$selector=$value"
            val msg = MessageBuilder.withPayload(factoidText).build()
            eventGateway.process(msg)
        } catch (e: Exception) {
            logger.debug("Could not cache spec as factoid: {}", e.message)
        }
    }

    private fun toRequest(typeToken: String, identifier: Int): SpecRequest? {
        val type =
            try {
                SpecType.valueOf(typeToken.uppercase())
            } catch (_: IllegalArgumentException) {
                return null
            }
        return SpecRequest(type, identifier)
    }

    private companion object {
        private val matcher =
            CommandPatternMatcher(
                listOf(
                    CommandPattern(
                        name = "rfc",
                        literals = listOf("rfc"),
                        args = listOf(CommandArgSpec("identifier", PositiveIntArgType)),
                    ),
                    CommandPattern(
                        name = "jep",
                        literals = listOf("jep"),
                        args = listOf(CommandArgSpec("identifier", PositiveIntArgType)),
                    ),
                    CommandPattern(
                        name = "jsr",
                        literals = listOf("jsr"),
                        args = listOf(CommandArgSpec("identifier", PositiveIntArgType)),
                    ),
                    CommandPattern(
                        name = "pep",
                        literals = listOf("pep"),
                        args = listOf(CommandArgSpec("identifier", PositiveIntArgType)),
                    ),
                )
            )
    }
}
