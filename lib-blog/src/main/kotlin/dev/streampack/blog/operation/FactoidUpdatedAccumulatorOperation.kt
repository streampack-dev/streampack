/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.service.FactoidUpdateBuffer
import dev.streampack.core.model.Consumed
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.service.TypedOperation
import dev.streampack.factoid.model.FactoidUpdatedEvent
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

@Component
class FactoidUpdatedAccumulatorOperation(private val factoidUpdateBuffer: FactoidUpdateBuffer) :
    TypedOperation<FactoidUpdatedEvent>(FactoidUpdatedEvent::class) {

    override val operationGroup: String = "blog"

    override fun handle(payload: FactoidUpdatedEvent, message: Message<*>): OperationOutcome {
        factoidUpdateBuffer.record(payload.selector)
        logger.debug("Queued factoid selector {} for deferred post rerender", payload.selector)
        return Consumed("queued factoid selector ${payload.selector}")
    }
}
