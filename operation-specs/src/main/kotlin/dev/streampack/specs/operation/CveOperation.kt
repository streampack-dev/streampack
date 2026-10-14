/* Joseph B. Ottinger (C)2026 */
package dev.streampack.specs.operation

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.ThrottlePolicy
import dev.streampack.core.parser.CommandArgSpec
import dev.streampack.core.parser.CommandMatchResult
import dev.streampack.core.parser.CommandPattern
import dev.streampack.core.parser.CommandPatternMatcher
import dev.streampack.core.parser.StringArgType
import dev.streampack.core.service.TranslatingOperation
import dev.streampack.specs.config.CveProperties
import dev.streampack.specs.model.CveRequest
import dev.streampack.specs.service.CveReporter
import org.springframework.messaging.Message
import org.springframework.messaging.support.MessageBuilder
import org.springframework.stereotype.Component

/**
 * `cve <term> [version]` (#194): a CVE by id, or the CVEs known in a package, by coordinates or by
 * name. See [CveReporter] for how a term is resolved and answered.
 *
 * Like [SpecsOperation] it runs after the factoid get operation (priority 90), so a factoid named
 * `cve` would win. An id lookup seeds one light factoid, the id with its cve.org link and nothing
 * else, since scores and descriptions get revised; package answers change too often to seed any.
 *
 * Each lookup can call three upstreams, so it's throttled per channel, by default to 10 an hour
 * (`streampack.specs.cve.throttle-requests` and `throttle-window`, #197).
 */
@Component
class CveOperation(
    private val reporter: CveReporter,
    private val eventGateway: EventGateway,
    properties: CveProperties,
) : TranslatingOperation<CveRequest>(CveRequest::class) {

    override val priority: Int = 95
    override val addressed: Boolean = true
    override val operationGroup: String = "specs"
    override val throttlePolicy: ThrottlePolicy =
        ThrottlePolicy(properties.throttleRequests, properties.throttleWindow)

    override fun translate(payload: String, message: Message<*>): CveRequest? {
        val match = matcher.match(payload.trim()) as? CommandMatchResult.Match ?: return null
        val term = match.captures["term"] as String
        val version = match.captures["version"] as String?
        if (!TERM.matches(term) || (version != null && !VERSION.matches(version))) return null
        return CveRequest(term, version)
    }

    override fun handle(payload: CveRequest, message: Message<*>): OperationOutcome? {
        val answer = reporter.answer(payload)
        answer.factoid?.let { (selector, value) -> seedFactoid(selector, value) }
        return OperationResult.Success(answer.text)
    }

    private fun seedFactoid(selector: String, value: String) {
        try {
            eventGateway.process(MessageBuilder.withPayload("$selector=$value").build())
        } catch (e: Exception) {
            logger.debug("Could not seed CVE factoid: {}", e.message)
        }
    }

    private companion object {
        /**
         * An id, coordinates (with an ecosystem's own characters: `@scope/name`, Go paths) or a
         * name.
         */
        private val TERM = Regex("[A-Za-z0-9_.@/:\\-]+")
        private val VERSION = Regex("[A-Za-z0-9_.+\\-]+")

        private val matcher =
            CommandPatternMatcher(
                listOf(
                    CommandPattern(
                        name = "cve",
                        literals = listOf("cve"),
                        args = listOf(CommandArgSpec("term", StringArgType)),
                    ),
                    CommandPattern(
                        name = "cve-version",
                        literals = listOf("cve"),
                        args =
                            listOf(
                                CommandArgSpec("term", StringArgType),
                                CommandArgSpec("version", StringArgType),
                            ),
                    ),
                ),
            )
    }
}
