/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.TypedOperation
import dev.streampack.taxonomy.TagNames
import dev.streampack.taxonomy.model.FindBlogCategoryTaxonomyRequest
import dev.streampack.taxonomy.model.FindBlogTagTaxonomyRequest
import dev.streampack.taxonomy.model.FindFactoidTagTaxonomyRequest
import dev.streampack.taxonomy.model.FindTaxonomySnapshotRequest
import dev.streampack.taxonomy.model.TaxonomySnapshot
import dev.streampack.taxonomy.model.TaxonomyTermCount
import org.springframework.messaging.Message
import org.springframework.messaging.support.MessageBuilder
import org.springframework.stereotype.Component

/** Collates taxonomy counts: post tags, factoid tags and categories apart, and their union. */
@Component
class FindTaxonomySnapshotOperation(private val eventGateway: EventGateway) :
    TypedOperation<FindTaxonomySnapshotRequest>(FindTaxonomySnapshotRequest::class) {

    override fun handle(
        payload: FindTaxonomySnapshotRequest,
        message: Message<*>,
    ): OperationOutcome {
        val provenance =
            (message.headers[Provenance.HEADER] as? Provenance)
                ?: Provenance(
                    protocol = Protocol.HTTP,
                    serviceId = "blog-service",
                    replyTo = "taxonomy",
                )

        val blogTags = dispatchTerms(FindBlogTagTaxonomyRequest, provenance)
        val factoidTags = dispatchTerms(FindFactoidTagTaxonomyRequest, provenance)
        val categoryTerms = dispatchTerms(FindBlogCategoryTaxonomyRequest, provenance)

        // Tag boards list posts only, so post tag counts stand apart from factoid uses.
        val tags = mergeCounts(blogTags)
        val factoidTagCounts = mergeCounts(factoidTags)
        val categories = mergeCounts(categoryTerms)
        val aggregate = mergeCounts(blogTags, factoidTags, categoryTerms)

        return OperationResult.Success(
            TaxonomySnapshot(
                tags = tags,
                categories = categories,
                aggregate = aggregate,
                factoidTags = factoidTagCounts,
            )
        )
    }

    private fun dispatchTerms(request: Any, provenance: Provenance): List<TaxonomyTermCount> {
        val message =
            MessageBuilder.withPayload(request).setHeader(Provenance.HEADER, provenance).build()

        return when (val result = eventGateway.process(message)) {
            is OperationResult.Success ->
                (result.payload as? List<*>)?.filterIsInstance<TaxonomyTermCount>().orEmpty()
            else -> emptyList()
        }
    }

    private fun mergeCounts(vararg lists: List<TaxonomyTermCount>): Map<String, Long> {
        val counts = mutableMapOf<String, Long>()
        for (list in lists) {
            for (entry in list) {
                // As stored, not normalized: the Atlas matches these keys to stored tags.
                val key = TagNames.stored(entry.name) ?: continue
                if (TagNames.isSystem(key)) continue
                counts[key] = (counts[key] ?: 0L) + entry.count
            }
        }
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .associate { it.key to it.value }
    }
}
