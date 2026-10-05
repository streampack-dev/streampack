/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.operation

import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.service.TypedOperation
import dev.streampack.factoid.model.FactoidAttributeType
import dev.streampack.factoid.model.FactoidCatalog
import dev.streampack.factoid.model.FactoidCatalogEntry
import dev.streampack.factoid.model.FindFactoidCatalogRequest
import dev.streampack.factoid.repository.FactoidAttributeRepository
import dev.streampack.factoid.repository.FactoidRepository
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/**
 * Every factoid's selector and definition text, for matching against writing (#130): the editor's
 * suggestions, drafting, the graph report. Internal, so not addressed.
 */
@Component
class FindFactoidCatalogOperation(
    private val factoidRepository: FactoidRepository,
    private val attributeRepository: FactoidAttributeRepository,
) : TypedOperation<FindFactoidCatalogRequest>(FindFactoidCatalogRequest::class) {
    override val addressed: Boolean = false

    override fun handle(payload: FindFactoidCatalogRequest, message: Message<*>): OperationOutcome {
        val texts =
            attributeRepository.findAllOfTypeWithFactoid(FactoidAttributeType.TEXT).associate {
                it.factoid.selector.lowercase() to it.attributeValue?.trim()?.ifBlank { null }
            }
        val entries =
            factoidRepository.findAll().map { factoid ->
                FactoidCatalogEntry(factoid.selector, texts[factoid.selector.lowercase()])
            }
        return OperationResult.Success(FactoidCatalog(entries))
    }
}
