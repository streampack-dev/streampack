/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.model.DeriveFactoidsRequest
import dev.streampack.blog.model.DeriveFactoidsResponse
import dev.streampack.blog.model.FactoidMention
import dev.streampack.blog.model.MissingFactoid
import dev.streampack.blog.service.MarkdownRenderingService
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.service.TypedOperation
import dev.streampack.factoid.model.FactoidCatalog
import dev.streampack.factoid.model.FactoidMatcher
import dev.streampack.factoid.model.FindFactoidCatalogRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.messaging.Message
import org.springframework.messaging.support.MessageBuilder
import org.springframework.stereotype.Component

/**
 * The factoids a draft mentions, for its author to link, and its `[[…]]` links to factoids that
 * don't exist, for them to create (#130, #114). Dictionary matching against the factoids (see
 * [FactoidMatcher]), on the draft's prose: code and links are skipped. No AI; nothing stored.
 * Signed-in readers only, as it reads every factoid.
 */
@Component
class DeriveFactoidsOperation(
    private val eventGateway: EventGateway,
    private val markdown: MarkdownRenderingService,
    @Value("\${streampack.blog.factoid-mentions.stopwords:#{null}}") stopwords: List<String>?,
) : TypedOperation<DeriveFactoidsRequest>(DeriveFactoidsRequest::class) {
    private val stopwords = stopwords ?: FactoidMatcher.DEFAULT_STOPWORDS

    override fun handle(payload: DeriveFactoidsRequest, message: Message<*>): OperationOutcome {
        requireRole(message, Role.USER)?.let {
            return it
        }
        if (payload.markdownSource.isBlank()) return OperationResult.Error("Content is required")
        val catalog = catalog() ?: return OperationResult.Error("Factoids are unavailable")
        val source = markdown.factoidMentionSource(payload.markdownSource)
        val linked = source.links.map { it.selector.lowercase() }.toSet()

        val mentions =
            FactoidMatcher(catalog, stopwords)
                .find(source.prose)
                .groupBy { it.selector }
                .map { (selector, found) ->
                    FactoidMention(
                        selector = selector,
                        term = found.first().term,
                        occurrences = found.size,
                        definition = catalog.find(selector)?.text.orEmpty(),
                        linked = selector.lowercase() in linked,
                    )
                }
        val missing =
            source.links
                .filter { catalog.find(it.selector) == null }
                .distinctBy { it.selector.lowercase() }
                .map { MissingFactoid(selector = it.selector, term = it.term) }
        return OperationResult.Success(DeriveFactoidsResponse(mentions, missing))
    }

    private fun catalog(): FactoidCatalog? {
        val message =
            MessageBuilder.withPayload(FindFactoidCatalogRequest as Any)
                .setHeader(
                    Provenance.HEADER,
                    Provenance(
                        protocol = Protocol.HTTP,
                        serviceId = "blog-service",
                        replyTo = "factoids",
                    ),
                )
                .build()
        return (eventGateway.process(message) as? OperationResult.Success)?.payload
            as? FactoidCatalog
    }
}
