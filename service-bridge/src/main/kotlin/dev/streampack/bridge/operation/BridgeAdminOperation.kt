/* Joseph B. Ottinger (C)2026 */
package dev.streampack.bridge.operation

import dev.streampack.bridge.service.BridgeService
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.service.TypedOperation
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Admin commands for managing directional bridge pairs. Requires ADMIN role. */
@Component
class BridgeAdminOperation(private val bridgeService: BridgeService) :
    TypedOperation<String>(String::class) {

    override fun canHandle(payload: String, message: Message<*>): Boolean {
        val trimmed = payload.trim()
        return trimmed == "bridge" || trimmed.startsWith("bridge ")
    }

    override fun handle(payload: String, message: Message<*>): OperationOutcome {
        val provenance = message.headers[Provenance.HEADER] as? Provenance

        val args = payload.trim().removePrefix("bridge").trim()
        if (args.isBlank()) return OperationResult.Success(helpText())

        val tokens = args.split("\\s+".toRegex())
        val subcommand = tokens[0]

        // Provenance discovery and bridge info are available to all users
        if (subcommand == "provenance") {
            return handleProvenance(provenance)
        }
        if (subcommand == "info") {
            return handleInfo(provenance)
        }

        requireRole(message, Role.ADMIN)?.let {
            return it
        }

        return when (subcommand) {
            "copy" -> handleCopy(tokens.drop(1))
            "remove" -> handleRemove(tokens.drop(1))
            "list" -> handleList()
            else ->
                OperationResult.Error(
                    "Unknown bridge subcommand '$subcommand'. Use 'bridge' for available commands."
                )
        }
    }

    private fun handleCopy(args: List<String>): OperationResult {
        if (args.size < 2) {
            return OperationResult.Error("Usage: bridge copy <source-uri> <target-uri>")
        }
        val sourceUri = args[0]
        val targetUri = args[1]

        return when (val result = bridgeService.copy(sourceUri, targetUri)) {
            is BridgeService.CopyResult.Success ->
                OperationResult.Success("Bridge established: $sourceUri -> $targetUri")
            is BridgeService.CopyResult.AlreadyExists ->
                OperationResult.Success("Bridge already exists: $sourceUri -> $targetUri")
            is BridgeService.CopyResult.Error -> OperationResult.Error(result.message)
        }
    }

    private fun handleRemove(args: List<String>): OperationResult {
        if (args.size < 2) {
            return OperationResult.Error("Usage: bridge remove <source-uri> <target-uri>")
        }
        val sourceUri = args[0]
        val targetUri = args[1]
        val removed = bridgeService.removeCopy(sourceUri, targetUri)
        return if (removed) {
            OperationResult.Success("Bridge removed: $sourceUri -> $targetUri")
        } else {
            OperationResult.Error("No bridge found from $sourceUri to $targetUri")
        }
    }

    private fun handleList(): OperationResult {
        val pairs = bridgeService.listAll()
        if (pairs.isEmpty()) {
            return OperationResult.Success("No bridge pairs configured")
        }
        val lines =
            pairs.map { pair ->
                val directions = mutableListOf<String>()
                if (pair.copyFirstToSecond) directions.add("${pair.firstUri} -> ${pair.secondUri}")
                if (pair.copySecondToFirst) directions.add("${pair.secondUri} -> ${pair.firstUri}")
                "  " + directions.joinToString(", ")
            }
        return OperationResult.Success("Bridge pairs:\n${lines.joinToString("\n")}")
    }

    private fun handleInfo(provenance: Provenance?): OperationResult {
        if (provenance == null) {
            return OperationResult.Error("No provenance available for this channel")
        }
        val uri = provenance.encode()
        val pair =
            bridgeService.findPairFor(uri)
                ?: return OperationResult.Success("No bridge configured for this channel")

        val partnerUri = if (pair.firstUri == uri) pair.secondUri else pair.firstUri
        val directions = mutableListOf<String>()
        if (pair.firstUri == uri && pair.copyFirstToSecond) directions.add("copy to $partnerUri")
        if (pair.secondUri == uri && pair.copySecondToFirst) directions.add("copy to $partnerUri")
        if (pair.firstUri == uri && pair.copySecondToFirst) directions.add("copy from $partnerUri")
        if (pair.secondUri == uri && pair.copyFirstToSecond) directions.add("copy from $partnerUri")

        return OperationResult.Success("Bridge: ${directions.joinToString(", ")}")
    }

    private fun handleProvenance(provenance: Provenance?): OperationResult {
        if (provenance == null) {
            return OperationResult.Error("No provenance available for this channel")
        }
        return OperationResult.Success(provenance.encode())
    }

    private fun helpText(): String =
        """
        |Bridge Commands:
        |  bridge provenance                         - Show this channel's provenance URI
        |  bridge info                               - Show bridge for this channel
        |Bridge Admin Commands (requires ADMIN):
        |  bridge copy <source-uri> <target-uri>     - Copy source content to target
        |  bridge remove <source-uri> <target-uri>   - Remove directional copy
        |  bridge list                               - Show all bridge pairs
        """
            .trimMargin()
}
