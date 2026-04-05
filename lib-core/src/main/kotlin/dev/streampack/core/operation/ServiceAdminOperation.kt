/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.operation

import com.enigmastation.streampack.core.model.OperationOutcome
import com.enigmastation.streampack.core.model.OperationResult
import com.enigmastation.streampack.core.model.Role
import com.enigmastation.streampack.core.service.OperationConfigService
import com.enigmastation.streampack.core.service.TypedOperation
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Tier 1 admin commands for service enablement. Persists state; restart needed to take effect. */
@Component
class ServiceAdminOperation(private val configService: OperationConfigService) :
    TypedOperation<String>(String::class) {

    override fun canHandle(payload: String, message: Message<*>): Boolean {
        val trimmed = payload.trim()
        return trimmed == "service" || trimmed.startsWith("service ")
    }

    override fun handle(payload: String, message: Message<*>): OperationOutcome {
        val args = payload.trim().removePrefix("service").trim()
        if (args.isBlank()) return OperationResult.Success(helpText())

        val tokens = args.split("\\s+".toRegex())
        val subcommand = tokens[0]

        // Read commands are public
        if (subcommand == "list") return handleList()

        // Mutations require SUPER_ADMIN
        requireRole(message, Role.SUPER_ADMIN)?.let {
            return it
        }

        return when (subcommand) {
            "enable" -> handleEnable(tokens.drop(1))
            "disable" -> handleDisable(tokens.drop(1))
            else ->
                OperationResult.Error(
                    "Unknown service subcommand '$subcommand'. Use 'service' for available commands."
                )
        }
    }

    private fun handleEnable(args: List<String>): OperationResult {
        if (args.isEmpty()) return OperationResult.Error("Usage: service enable <name>")
        val name = args[0]
        configService.setEnabled("", "service:$name", true)
        return OperationResult.Success(
            "Service '$name' marked enabled. Restart required to take effect."
        )
    }

    private fun handleDisable(args: List<String>): OperationResult {
        if (args.isEmpty()) return OperationResult.Error("Usage: service disable <name>")
        val name = args[0]
        configService.setEnabled("", "service:$name", false)
        return OperationResult.Success(
            "Service '$name' marked disabled. Restart required to take effect."
        )
    }

    private fun handleList(): OperationResult {
        val configs = configService.findAll().filter { it.operationGroup.startsWith("service:") }
        if (configs.isEmpty()) return OperationResult.Success("No service configurations found.")
        val lines =
            configs.map {
                val status = if (it.enabled) "enabled" else "disabled"
                val name = it.operationGroup.removePrefix("service:")
                "  $name: $status"
            }
        return OperationResult.Success("Service configurations:\n${lines.joinToString("\n")}")
    }

    private fun helpText(): String =
        """
        |Service Admin Commands:
        |  service list                  -- show service configurations
        |  service enable <name>         -- mark service enabled (restart required)
        |  service disable <name>        -- mark service disabled (restart required)
        """
            .trimMargin()
}
