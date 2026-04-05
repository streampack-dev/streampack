/* Joseph B. Ottinger (C)2026 */
package dev.streampack.test

import com.enigmastation.streampack.core.model.OperationOutcome
import com.enigmastation.streampack.core.model.OperationResult
import com.enigmastation.streampack.core.service.TypedOperation
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/**
 * Deterministic test operation that produces predictable results for infrastructure testing.
 *
 * Commands:
 * - `trigger success <message>` -> OperationResult.Success(message)
 * - `trigger error <message>` -> OperationResult.Error(message)
 * - `trigger nothandled` -> OperationResult.NotHandled
 *
 * The message body is passed through verbatim, so reference tokens or other content can be embedded
 * for testing egress rendering.
 */
@Component
class TriggerOperation : TypedOperation<String>(String::class) {

    override val priority: Int = 5
    override val addressed: Boolean = true
    override val operationGroup: String = "test-trigger"

    override fun canHandle(payload: String, message: Message<*>): Boolean =
        payload.trim().startsWith("trigger ", ignoreCase = true)

    override fun handle(payload: String, message: Message<*>): OperationOutcome? {
        val body = payload.trim().removePrefix("trigger ").removePrefix("Trigger ")
        return when {
            body.startsWith("success ", ignoreCase = true) -> {
                val message = body.substringAfter(" ")
                OperationResult.Success(message)
            }
            body.startsWith("error ", ignoreCase = true) -> {
                val message = body.substringAfter(" ")
                OperationResult.Error(message)
            }
            body.equals("nothandled", ignoreCase = true) -> OperationResult.NotHandled
            else -> null
        }
    }
}
