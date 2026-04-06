/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.operation

import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.service.TypedOperation
import dev.streampack.factoid.entity.FactoidAttribute
import dev.streampack.factoid.model.FactoidAttributeType
import dev.streampack.factoid.model.FactoidLinkMetadataResponse
import dev.streampack.factoid.model.FindFactoidLinkMetadataRequest
import dev.streampack.factoid.repository.FactoidAttributeRepository
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Returns shared factoid link metadata for a selector via the operation pipeline. */
@Component
class FindFactoidLinkMetadataOperation(
    private val factoidAttributeRepository: FactoidAttributeRepository
) : TypedOperation<FindFactoidLinkMetadataRequest>(FindFactoidLinkMetadataRequest::class) {

    override fun handle(
        payload: FindFactoidLinkMetadataRequest,
        message: Message<*>,
    ): OperationOutcome {
        val selector = payload.selector.trim()
        if (selector.isBlank()) {
            return OperationResult.Success(FactoidLinkMetadataResponse(selector = ""))
        }

        val byType =
            factoidAttributeRepository.findByFactoidSelectorIgnoreCase(selector).associateBy {
                it.attributeType
            }

        return OperationResult.Success(
            FactoidLinkMetadataResponse(
                selector = selector,
                text = byType.attributeValue(FactoidAttributeType.TEXT),
                urls = byType.attributeValues(FactoidAttributeType.URLS),
                tags = byType.attributeValues(FactoidAttributeType.TAGS),
                seeAlso = byType.attributeValues(FactoidAttributeType.SEEALSO),
            )
        )
    }

    private fun Map<FactoidAttributeType, FactoidAttribute>.attributeValue(
        type: FactoidAttributeType
    ): String? {
        val value = this[type]?.attributeValue?.trim().orEmpty()
        return value.ifBlank { null }
    }

    private fun Map<FactoidAttributeType, FactoidAttribute>.attributeValues(
        type: FactoidAttributeType
    ): List<String> =
        this[type]
            ?.attributeValue
            .orEmpty()
            .split(",")
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
}
