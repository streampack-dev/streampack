/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.operation

import com.enigmastation.streampack.core.integration.EgressTransformer
import com.enigmastation.streampack.core.model.OperationOutcome
import com.enigmastation.streampack.core.model.OperationResult
import com.enigmastation.streampack.core.model.Provenance
import com.enigmastation.streampack.core.model.Role
import com.enigmastation.streampack.core.service.Operation
import com.enigmastation.streampack.core.service.OperationConfigService
import com.enigmastation.streampack.core.service.TypedOperation
import org.springframework.context.annotation.Lazy
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Tier 3 admin commands for per-provenance operation group enablement */
@Component
class ChannelConfigOperation(
    private val configService: OperationConfigService,
    @Lazy private val operations: List<Operation>,
    @Lazy private val transformers: List<EgressTransformer>,
) : TypedOperation<String>(String::class) {

    override fun canHandle(payload: String, message: Message<*>): Boolean {
        val trimmed = payload.trim()
        return trimmed == "channel" || trimmed.startsWith("channel ")
    }

    override fun handle(payload: String, message: Message<*>): OperationOutcome {
        val provenance = message.headers[Provenance.HEADER] as? Provenance
        val args = payload.trim().removePrefix("channel").trim()
        if (args.isBlank()) return OperationResult.Success(helpText())

        val tokens = args.split("\\s+".toRegex())
        val subcommand = tokens[0]

        // Read commands are public
        if (subcommand == "config") return handleConfig(tokens.drop(1), provenance)

        // Mutations require ADMIN
        requireRole(message, Role.ADMIN)?.let {
            return it
        }

        return when (subcommand) {
            "enable" -> handleEnable(tokens.drop(1), provenance)
            "disable" -> handleDisable(tokens.drop(1), provenance)
            "set" -> handleSet(tokens.drop(1), provenance)
            else ->
                OperationResult.Error(
                    "Unknown channel subcommand '$subcommand'. Use 'channel' for available commands."
                )
        }
    }

    private fun handleEnable(args: List<String>, provenance: Provenance?): OperationResult {
        if (args.isEmpty())
            return OperationResult.Error("Usage: channel enable <group> [for <pattern>]")
        val group = args[0]
        val pattern = extractPattern(args.drop(1), provenance) ?: return missingProvenance()
        if (!isKnownGroup(group)) {
            return OperationResult.Error(
                "Unknown operation group '$group'. Known groups: ${knownGroups()}"
            )
        }
        configService.setEnabled(pattern, group, true)
        return OperationResult.Success(
            "Operation group '$group' enabled for '${pattern.ifEmpty { "(global)" }}'."
        )
    }

    private fun handleDisable(args: List<String>, provenance: Provenance?): OperationResult {
        if (args.isEmpty())
            return OperationResult.Error("Usage: channel disable <group> [for <pattern>]")
        val group = args[0]
        val pattern = extractPattern(args.drop(1), provenance) ?: return missingProvenance()
        if (!isKnownGroup(group)) {
            return OperationResult.Error(
                "Unknown operation group '$group'. Known groups: ${knownGroups()}"
            )
        }
        configService.setEnabled(pattern, group, false)
        return OperationResult.Success(
            "Operation group '$group' disabled for '${pattern.ifEmpty { "(global)" }}'."
        )
    }

    private fun handleSet(args: List<String>, provenance: Provenance?): OperationResult {
        // channel set <group> <key> <value> [for <pattern>]
        if (args.size < 3) {
            return OperationResult.Error("Usage: channel set <group> <key> <value> [for <pattern>]")
        }
        val group = args[0]
        if (!isKnownGroup(group)) {
            return OperationResult.Error(
                "Unknown operation group '$group'. Known groups: ${knownGroups()}"
            )
        }

        // Find "for" keyword to separate value from pattern
        val forIndex = args.indexOf("for")
        val key: String
        val value: String
        val pattern: String?
        if (forIndex > 2) {
            key = args[1]
            value = args.subList(2, forIndex).joinToString(" ")
            pattern = extractPattern(args.drop(forIndex), provenance)
        } else {
            key = args[1]
            value = args.drop(2).joinToString(" ")
            pattern = extractPattern(emptyList(), provenance)
        }
        if (pattern == null) return missingProvenance()

        configService.setConfigValue(pattern, group, key, value)
        return OperationResult.Success(
            "Set '$key' = '$value' for group '$group' at '${pattern.ifEmpty { "(global)" }}'."
        )
    }

    private fun handleConfig(args: List<String>, provenance: Provenance?): OperationResult {
        val pattern = extractPattern(args, provenance) ?: return missingProvenance()
        val groups = knownGroups()
        if (groups.isEmpty()) return OperationResult.Success("No operation groups registered.")

        val lines =
            groups.map { group ->
                val enabled = configService.isOperationEnabled(pattern, group)
                val config = configService.getOperationConfig(pattern, group)
                val status = if (enabled) "enabled" else "disabled"
                val configStr = if (config.isEmpty()) "" else " $config"
                "  $group: $status$configStr"
            }
        val target = pattern.ifEmpty { "(global)" }
        return OperationResult.Success(
            "Resolved config for '$target':\n${lines.joinToString("\n")}"
        )
    }

    /**
     * Extracts the provenance pattern from a "for <pattern>" clause. When the clause is absent,
     * falls back to the current message's provenance URI.
     */
    private fun extractPattern(args: List<String>, provenance: Provenance?): String? {
        if (args.isNotEmpty() && args[0] == "for") {
            return if (args.size > 1) args.drop(1).joinToString(" ") else ""
        }
        // No "for" clause - use current provenance
        return provenance?.encode()
    }

    private fun missingProvenance(): OperationResult =
        OperationResult.Error("Cannot determine target provenance. Use 'for <pattern>' to specify.")

    private fun knownGroups(): List<String> =
        (operations.mapNotNull { it.operationGroup } + transformers.map { it.transformerGroup })
            .distinct()
            .sorted()

    private fun isKnownGroup(group: String): Boolean = group in knownGroups()

    private fun helpText(): String =
        """
        |Channel Config Commands:
        |  channel config [for <pattern>]                      -- show resolved config
        |  channel enable <group> [for <pattern>]              -- enable group for provenance
        |  channel disable <group> [for <pattern>]             -- disable group for provenance
        |  channel set <group> <key> <value> [for <pattern>]   -- set config for provenance
        |
        |When 'for' is omitted, the current channel's provenance is used.
        """
            .trimMargin()
}
