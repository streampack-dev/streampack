/* Joseph B. Ottinger (C)2026 */
package dev.streampack.example

import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.service.TypedOperation
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/**
 * `greet <name>` answers "Hello, <name>, from an extension." It's the example extension (#229): a
 * jar in the server's ext/ directory, found by the server's scan of `dev.streampack`.
 */
@Component
class GreetOperation : TypedOperation<String>(String::class) {
    override val operationGroup: String = "greet"

    override fun canHandle(payload: String, message: Message<*>): Boolean =
        payload.trim().startsWith("greet ", ignoreCase = true)

    override fun handle(payload: String, message: Message<*>): OperationOutcome {
        val name = payload.trim().substringAfter(' ').trim()
        return OperationResult.Success("Hello, $name, from an extension.")
    }
}
