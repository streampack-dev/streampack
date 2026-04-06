/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.operation

import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.service.TypedOperation
import dev.streampack.factoid.repository.FactoidAttributeRepository
import dev.streampack.taxonomy.model.FindFactoidTagTaxonomyRequest
import dev.streampack.taxonomy.model.TaxonomyTermCount
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Provides factoid tag counts for taxonomy aggregation. */
@Component
class FindFactoidTagTaxonomyOperation(
    private val factoidAttributeRepository: FactoidAttributeRepository
) : TypedOperation<FindFactoidTagTaxonomyRequest>(FindFactoidTagTaxonomyRequest::class) {

    override fun handle(
        payload: FindFactoidTagTaxonomyRequest,
        message: Message<*>,
    ): OperationOutcome {
        val tags =
            factoidAttributeRepository.findTagCounts().map { TaxonomyTermCount(it.name, it.count) }
        return OperationResult.Success(tags)
    }
}
