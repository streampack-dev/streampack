/* Joseph B. Ottinger (C)2026 */
package dev.streampack.urltitle.operation

import dev.streampack.core.extensions.compress
import dev.streampack.core.extensions.joinToStringWithAnd
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.service.TypedOperation
import dev.streampack.urltitle.service.UrlTitleService
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Admin commands for managing the URL title ignored-hosts list */
@Component
class ManageIgnoredHostsOperation(private val urlTitleService: UrlTitleService) :
    TypedOperation<String>(String::class) {

    override val priority: Int = 50
    override val addressed: Boolean = true
    override val operationGroup: String = "urltitle"

    override fun canHandle(payload: String, message: Message<*>): Boolean {
        return payload.compress().lowercase().startsWith("url ignore ")
    }

    override fun handle(payload: String, message: Message<*>): OperationOutcome? {
        val commands = payload.compress().lowercase().removePrefix("url ignore ").split(' ')
        return try {
            when (commands[0]) {
                "list" -> {
                    val hosts =
                        urlTitleService
                            .findAllIgnoredHosts()
                            .shuffled()
                            .take(7)
                            .joinToStringWithAnd()
                    OperationResult.Success("Ignored hosts include: $hosts")
                }
                "add" -> {
                    if (commands.size < 2) {
                        return OperationResult.Error("Usage: url ignore add <hostname>")
                    }
                    urlTitleService.addIgnoredHost(commands[1])
                    OperationResult.Success("Added ${commands[1]} to ignored hosts.")
                }
                "delete" -> {
                    if (commands.size < 2) {
                        return OperationResult.Error("Usage: url ignore delete <hostname>")
                    }
                    urlTitleService.deleteIgnoredHost(commands[1])
                    OperationResult.Success("Removed ${commands[1]} from ignored hosts.")
                }
                else ->
                    OperationResult.Error(
                        "Unknown subcommand: ${commands[0]}. Use list, add, or delete."
                    )
            }
        } catch (e: Exception) {
            logger.warn("Error handling ignored hosts command: {}", e.message)
            OperationResult.Error("Failed to process command: ${e.message}")
        }
    }
}
