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
import dev.streampack.taxonomy.TagNames
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
         * A `tags` attribute's tags as stored ([TagNames.splitStored]): split on commas, trimmed,
         * lowercased, no blanks or system tags. Not [TagNames.normalize]: the Atlas keys places by
         * these and counts them with the factoid tag SQL, which reads stored tags the same way.
         */
        fun tagsOf(value: String?): List<String> =
            TagNames.splitStored(value).filterNot(TagNames::isSystem).distinct()
    }
}
