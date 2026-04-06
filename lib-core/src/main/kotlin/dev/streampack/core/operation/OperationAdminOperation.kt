/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.operation

import dev.streampack.core.integration.EgressTransformer
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Role
import dev.streampack.core.service.Operation
import dev.streampack.core.service.OperationConfigService
import dev.streampack.core.service.TypedOperation
import org.springframework.context.annotation.Lazy
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Tier 2 admin commands for global operation group enablement */
@Component
class OperationAdminOperation(
    private val configService: OperationConfigService,
    @Lazy private val operations: List<Operation>,
    @Lazy private val transformers: List<EgressTransformer>,
) : TypedOperation<String>(String::class) {

    override fun canHandle(payload: String, message: Message<*>): Boolean {
        val trimmed = payload.trim()
        return trimmed == "operation" || trimmed.startsWith("operation ")
    }

    override fun handle(payload: String, message: Message<*>): OperationOutcome {
        val args = payload.trim().removePrefix("operation").trim()
        if (args.isBlank()) return OperationResult.Success(helpText())

        val tokens = args.split("\\s+".toRegex())
        val subcommand = tokens[0]

        // Read commands are public
        if (subcommand == "config") return handleConfig()

        // Mutations require ADMIN
        requireRole(message, Role.ADMIN)?.let {
            return it
        }

        return when (subcommand) {
            "enable" -> handleEnable(tokens.drop(1))
            "disable" -> handleDisable(tokens.drop(1))
            "set" -> handleSet(tokens.drop(1))
            else ->
                OperationResult.Error(
                    "Unknown operation subcommand '$subcommand'. Use 'operation' for available commands."
                )
        }
    }

    private fun handleEnable(args: List<String>): OperationResult {
        if (args.isEmpty()) return OperationResult.Error("Usage: operation enable <group>")
        val group = args[0]
        if (!isKnownGroup(group)) {
            return OperationResult.Error(
                "Unknown operation group '$group'. Known groups: ${knownGroups()}"
            )
        }
        configService.setEnabled("", group, true)
        return OperationResult.Success("Operation group '$group' enabled globally.")
    }

    private fun handleDisable(args: List<String>): OperationResult {
        if (args.isEmpty()) return OperationResult.Error("Usage: operation disable <group>")
        val group = args[0]
        if (!isKnownGroup(group)) {
            return OperationResult.Error(
                "Unknown operation group '$group'. Known groups: ${knownGroups()}"
            )
        }
        configService.setEnabled("", group, false)
        return OperationResult.Success("Operation group '$group' disabled globally.")
    }

    private fun handleSet(args: List<String>): OperationResult {
        if (args.size < 3)
            return OperationResult.Error("Usage: operation set <group> <key> <value>")
        val group = args[0]
        val key = args[1]
        val value = args.drop(2).joinToString(" ")
        if (!isKnownGroup(group)) {
            return OperationResult.Error(
                "Unknown operation group '$group'. Known groups: ${knownGroups()}"
            )
        }
        configService.setConfigValue("", group, key, value)
        return OperationResult.Success("Set '$key' = '$value' for operation group '$group'.")
    }

    private fun handleConfig(): OperationResult {
        val groups = knownGroups()
        if (groups.isEmpty()) return OperationResult.Success("No operation groups registered.")
        val lines =
            groups.map { group ->
                val config = configService.findConfig("", group)
                val status = if (config?.enabled != false) "enabled" else "disabled"
                val configMap = config?.config ?: emptyMap()
                val configStr = if (configMap.isEmpty()) "" else " $configMap"
                "  $group: $status$configStr"
            }
        return OperationResult.Success("Global operation config:\n${lines.joinToString("\n")}")
    }

    private fun knownGroups(): List<String> =
        (operations.mapNotNull { it.operationGroup } + transformers.map { it.transformerGroup })
            .distinct()
            .sorted()

    private fun isKnownGroup(group: String): Boolean = group in knownGroups()

    private fun helpText(): String =
        """
        |Operation Admin Commands:
        |  operation config                       -- show global operation config
        |  operation enable <group>               -- enable group globally
        |  operation disable <group>              -- disable group globally
        |  operation set <group> <key> <value>    -- set config key globally
        """
            .trimMargin()
}
