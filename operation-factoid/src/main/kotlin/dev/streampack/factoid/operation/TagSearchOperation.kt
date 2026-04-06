/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.operation

import dev.streampack.core.extensions.compress
import dev.streampack.core.extensions.joinToStringWithAnd
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.service.TranslatingOperation
import dev.streampack.factoid.model.TagSearchRequest
import dev.streampack.factoid.service.FactoidService
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Handles addressed tag search: "tag term" */
@Component
class TagSearchOperation(private val factoidService: FactoidService) :
    TranslatingOperation<TagSearchRequest>(TagSearchRequest::class) {

    override val priority: Int = 65
    override val addressed: Boolean = true
    override val operationGroup: String = "factoid"

    override fun translate(payload: String, message: Message<*>): TagSearchRequest? {
        val compressed = payload.compress()
        if (!compressed.startsWith("tag ", ignoreCase = true)) return null
        val tag = compressed.substringAfter("tag ", "").trim()
        if (tag.isBlank()) return null
        return TagSearchRequest(tag)
    }

    override fun handle(payload: TagSearchRequest, message: Message<*>): OperationOutcome {
        val selectors = factoidService.searchByTag(payload.tag)
        return if (selectors.isEmpty()) {
            OperationResult.Success("No factoids found with tag '${payload.tag}'.")
        } else {
            val refSelectors = selectors.map { "{{ref:$it}}" }
            val joined = refSelectors.joinToStringWithAnd()
            val display =
                if (joined.length > 200) {
                    selectors.take(10).map { "{{ref:$it}}" }.joinToStringWithAnd()
                } else {
                    joined
                }
            OperationResult.Success("Factoids tagged '${payload.tag}': $display")
        }
    }
}
