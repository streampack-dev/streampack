/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.repository.PostTagRepository
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.service.TypedOperation
import dev.streampack.taxonomy.model.FindBlogTagTaxonomyRequest
import dev.streampack.taxonomy.model.TaxonomyTermCount
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Provides blog tag counts for taxonomy aggregation. */
@Component
class FindBlogTagTaxonomyOperation(private val postTagRepository: PostTagRepository) :
    TypedOperation<FindBlogTagTaxonomyRequest>(FindBlogTagTaxonomyRequest::class) {

    override fun handle(
        payload: FindBlogTagTaxonomyRequest,
        message: Message<*>,
    ): OperationOutcome {
        val tags = postTagRepository.findTagCounts().map { TaxonomyTermCount(it.name, it.count) }
        return OperationResult.Success(tags)
    }
}
