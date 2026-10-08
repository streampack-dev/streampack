/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.operation

import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.service.TypedOperation
import dev.streampack.factoid.model.FactoidAttributeType
import dev.streampack.factoid.model.FactoidTagEntry
import dev.streampack.factoid.model.FactoidTagging
import dev.streampack.factoid.model.FindFactoidTaggingRequest
import dev.streampack.factoid.repository.FactoidAttributeRepository
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/**
 * Every tagged factoid's tags and times, for the Atlas's ties and pins (ui-pudl#184). Tags are read
 * as [FactoidAttributeRepository.findTagCounts] counts them, so the Atlas and the taxonomy agree.
 * Internal, so not addressed.
 */
@Component
class FindFactoidTaggingOperation(private val attributeRepository: FactoidAttributeRepository) :
    TypedOperation<FindFactoidTaggingRequest>(FindFactoidTaggingRequest::class) {
    override val addressed: Boolean = false

    override fun handle(payload: FindFactoidTaggingRequest, message: Message<*>): OperationOutcome {
        val entries =
            attributeRepository
                .findAllOfTypeWithFactoid(FactoidAttributeType.TAGS)
                .mapNotNull { attribute ->
                    val tags = tagsOf(attribute.attributeValue)
                    if (tags.isEmpty()) return@mapNotNull null
                    val factoid = attribute.factoid
                    FactoidTagEntry(factoid.selector, tags, factoid.createdAt, factoid.updatedAt)
                }
                .sortedBy { it.selector.lowercase() }
        return OperationResult.Success(FactoidTagging(entries))
    }

    companion object {
        /**
         * A `tags` attribute's tags: split on commas, trimmed, lowercased, no blanks or `_` tags.
         */
        fun tagsOf(value: String?): List<String> =
            value
                .orEmpty()
                .split(',')
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() && !it.startsWith("_") }
                .distinct()
    }
}
