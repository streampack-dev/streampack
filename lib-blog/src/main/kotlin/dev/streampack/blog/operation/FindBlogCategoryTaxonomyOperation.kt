/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.repository.PostCategoryRepository
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.service.TypedOperation
import dev.streampack.taxonomy.model.FindBlogCategoryTaxonomyRequest
import dev.streampack.taxonomy.model.TaxonomyTermCount
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Provides blog category counts for taxonomy aggregation. */
@Component
class FindBlogCategoryTaxonomyOperation(
    private val postCategoryRepository: PostCategoryRepository
) : TypedOperation<FindBlogCategoryTaxonomyRequest>(FindBlogCategoryTaxonomyRequest::class) {

    override fun handle(
        payload: FindBlogCategoryTaxonomyRequest,
        message: Message<*>,
    ): OperationOutcome {
        val categories =
            postCategoryRepository.findCategoryCounts().map { TaxonomyTermCount(it.name, it.count) }
        return OperationResult.Success(categories)
    }
}
