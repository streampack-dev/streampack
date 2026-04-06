/* Joseph B. Ottinger (C)2026 */
package dev.streampack.polling.operation

import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.service.TypedOperation
import org.springframework.messaging.Message

/**
 * Abstract command dispatcher for polling source management operations.
 *
 * Handles the shared pattern of command prefix matching, subcommand extraction, "to <uri>" / "for
 * <uri>" destination parsing, and ADMIN auth for mutations. Subclasses provide the command prefix
 * and implement the five handler methods with pre-parsed inputs.
 */
abstract class PollingSourceManagementOperation : TypedOperation<String>(String::class) {

    /** Command prefix, e.g. "github" or "feed" */
    abstract val commandPrefix: String

    override val addressed: Boolean = true

    override fun canHandle(payload: String, message: Message<*>): Boolean {
        val lower = payload.trim().lowercase()
        val prefix = commandPrefix.lowercase()

        // Read commands are open to everyone
        if (
            lower == "$prefix list" ||
                lower == "$prefix subscriptions" ||
                lower.startsWith("$prefix subscriptions for ")
        ) {
            return true
        }

        // Mutation commands require ADMIN
        val isMutation =
            lower.startsWith("$prefix subscribe ") ||
                lower.startsWith("$prefix unsubscribe ") ||
                lower.startsWith("$prefix remove ")
        if (!isMutation) {
            logger.debug("Payload '{}' is not a {} mutation command", lower, commandPrefix)
            return false
        }

        val allowed = hasRole(message, Role.ADMIN)
        if (!allowed) {
            logger.debug("Mutation '{}' denied (need ADMIN+)", lower)
        }
        return allowed
    }

    override fun handle(payload: String, message: Message<*>): OperationOutcome {
        val trimmed = payload.trim()
        val lower = trimmed.lowercase()
        val prefix = commandPrefix.lowercase()

        return when {
            lower == "$prefix list" -> onList()
            lower == "$prefix subscriptions" || lower.startsWith("$prefix subscriptions for ") ->
                handleSubscriptions(trimmed, message)
            lower.startsWith("$prefix subscribe ") -> {
                val remainder = trimmed.substring("$prefix subscribe ".length).trim()
                handleSubscribe(remainder, message)
            }
            lower.startsWith("$prefix unsubscribe ") -> {
                val remainder = trimmed.substring("$prefix unsubscribe ".length).trim()
                handleUnsubscribe(remainder, message)
            }
            lower.startsWith("$prefix remove ") -> {
                val identifier = trimmed.substring("$prefix remove ".length).trim()
                handleRemove(identifier)
            }
            else -> OperationResult.NotHandled
        }
    }

    /** List all registered sources */
    abstract fun onList(): OperationOutcome

    /** Subscribe a destination to a source */
    abstract fun onSubscribe(identifier: String, destinationUri: String): OperationOutcome

    /** Unsubscribe a destination from a source */
    abstract fun onUnsubscribe(identifier: String, destinationUri: String): OperationOutcome

    /** List subscriptions for a destination */
    abstract fun onSubscriptions(destinationUri: String): OperationOutcome

    /** Remove (deactivate) a source and its subscriptions */
    abstract fun onRemove(identifier: String): OperationOutcome

    private fun handleSubscriptions(trimmed: String, message: Message<*>): OperationOutcome {
        val lower = trimmed.lowercase()
        val prefix = commandPrefix.lowercase()
        val destinationUri =
            if (lower.startsWith("$prefix subscriptions for ")) {
                val uri = trimmed.substring("$prefix subscriptions for ".length).trim()
                if (uri.isBlank()) {
                    return OperationResult.Error(
                        "Usage: $commandPrefix subscriptions for <provenance-uri>"
                    )
                }
                uri
            } else {
                destinationUri(message)
                    ?: return OperationResult.Error("No provenance available for this message")
            }
        return onSubscriptions(destinationUri)
    }

    private fun handleSubscribe(remainder: String, message: Message<*>): OperationOutcome {
        if (remainder.isBlank()) {
            return OperationResult.Error(
                "Usage: $commandPrefix subscribe <identifier> [to <provenance-uri>]"
            )
        }
        val parsed = parseIdentifierAndTarget(remainder, message)
        if (parsed == null) {
            logger.debug("No provenance available for subscribe command")
            return OperationResult.Error("No provenance available for this message")
        }
        val (identifier, destinationUri) = parsed
        logger.debug(
            "Dispatching {} subscribe: identifier='{}', destination='{}'",
            commandPrefix,
            identifier,
            destinationUri,
        )
        return onSubscribe(identifier, destinationUri)
    }

    private fun handleUnsubscribe(remainder: String, message: Message<*>): OperationOutcome {
        if (remainder.isBlank()) {
            return OperationResult.Error(
                "Usage: $commandPrefix unsubscribe <identifier> [to <provenance-uri>]"
            )
        }
        val (identifier, destinationUri) =
            parseIdentifierAndTarget(remainder, message)
                ?: return OperationResult.Error("No provenance available for this message")
        return onUnsubscribe(identifier, destinationUri)
    }

    private fun handleRemove(identifier: String): OperationOutcome {
        if (identifier.isBlank()) {
            return OperationResult.Error("Usage: $commandPrefix remove <identifier>")
        }
        return onRemove(identifier)
    }

    /**
     * Splits remainder into an identifier and destination URI. When the remainder contains " to
     * <uri>", the explicit URI is used as the destination. Otherwise the message provenance is the
     * destination. Returns null when no destination can be determined.
     */
    private fun parseIdentifierAndTarget(
        remainder: String,
        message: Message<*>,
    ): Pair<String, String>? {
        val toIndex = remainder.lowercase().lastIndexOf(" to ")
        if (toIndex >= 0) {
            val identifier = remainder.substring(0, toIndex).trim()
            val target = remainder.substring(toIndex + " to ".length).trim()
            if (identifier.isNotBlank() && target.isNotBlank()) {
                return identifier to target
            }
        }
        val destinationUri = destinationUri(message) ?: return null
        return remainder to destinationUri
    }

    /** Encode the message's provenance as a destination URI */
    private fun destinationUri(message: Message<*>): String? {
        val provenance = message.headers[Provenance.HEADER] as? Provenance ?: return null
        return provenance.encode()
    }
}
