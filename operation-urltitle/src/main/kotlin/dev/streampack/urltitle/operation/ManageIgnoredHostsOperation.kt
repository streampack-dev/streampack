/* Joseph B. Ottinger (C)2026 */
package dev.streampack.urltitle.operation

import dev.streampack.core.extensions.compress
import dev.streampack.core.extensions.joinToStringWithAnd
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Role
import dev.streampack.core.service.TypedOperation
import dev.streampack.urltitle.service.UrlTitleService
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/**
 * Commands for the URL title ignore list. Anyone may list it; adding and deleting entries need
 * ADMIN, since an entry silences titles for everyone in every channel.
 */
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
                    requireRole(message, Role.ADMIN)?.let {
                        return it
                    }
                    if (commands.size < 2) {
                        return OperationResult.Error(
                            "Usage: url ignore add <host | *.host | host/path>"
                        )
                    }
                    val entry = urlTitleService.addIgnoredHost(commands[1])
                    OperationResult.Success("Added $entry to ignored hosts.")
                }
                "delete" -> {
                    requireRole(message, Role.ADMIN)?.let {
                        return it
                    }
                    if (commands.size < 2) {
                        return OperationResult.Error(
                            "Usage: url ignore delete <host | *.host | host/path>"
                        )
                    }
                    val entry =
                        urlTitleService.deleteIgnoredHost(commands[1])
                            ?: return OperationResult.Error(
                                "${commands[1]} is not in the ignored hosts."
                            )
                    OperationResult.Success("Removed $entry from ignored hosts.")
                }
                else ->
                    OperationResult.Error(
                        "Unknown subcommand: ${commands[0]}. Use list, add, or delete."
                    )
            }
        } catch (e: IllegalArgumentException) {
            OperationResult.Error(e.message ?: "Invalid ignore-list entry")
        } catch (e: Exception) {
            logger.warn("Error handling ignored hosts command: {}", e.message)
            OperationResult.Error("Failed to process command: ${e.message}")
        }
    }
}
