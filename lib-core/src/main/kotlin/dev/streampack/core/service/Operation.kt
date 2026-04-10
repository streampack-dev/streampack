/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.model.Consumed
import dev.streampack.core.model.Declined
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.RedactionRule
import dev.streampack.core.model.Role
import dev.streampack.core.model.ThrottlePolicy
import java.time.Duration
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.messaging.Message

/**
 * A self-selecting message handler in the global operation chain.
 *
 * Operations are the workhorses of the event system. Every Operation is registered globally and
 * decides for itself which messages it can handle via [canHandle]. The [OperationService] calls all
 * operations in [priority] order, stopping at the first one that produces a terminal
 * [OperationResult].
 *
 * ## Implementing an Operation
 * 1. Set [priority] -- lower values run first. Use 0-20 for high priority, 50 for normal, 80+ for
 *    catch-alls.
 * 2. Implement [canHandle] as a cheap check. Inspect the Provenance header, payload type, or
 *    message metadata to decide if this operation is even relevant. Return false to skip entirely.
 * 3. Implement [execute] to do the actual work. Return an [OperationResult.Success] or
 *    [OperationResult.Error] for a definitive answer, [Declined] to pass with diagnostic info
 *    (logged by OperationService), [Consumed] to handle the message internally without egress, or
 *    null to silently pass to the next operation in the chain.
 *
 * ## Lifecycle
 *
 * Operations are Spring beans -- register them with @Component or @Bean. The [OperationService]
 * collects all Operation beans at startup and sorts them by priority.
 */
interface Operation {
    val logger: Logger
        get() = LoggerFactory.getLogger(javaClass)

    /** Execution order within the chain. Lower values run first. */
    val priority: Int
        get() = 50

    /**
     * Whether this operation requires the message to be explicitly addressed to the bot.
     *
     * When true (the default), protocol adapters that use trigger detection (like IRC) will only
     * dispatch a message to the chain if it starts with a signal character or the bot's nick. When
     * false, the adapter's pre-scan will check [canHandle] on unaddressed messages and submit them
     * if at least one non-addressed operation is interested.
     */
    val addressed: Boolean
        get() = true

    /**
     * Redaction rules for text commands that include secrets. The ingress logging interceptor
     * applies these before persisting messages so that credentials never reach the message log.
     * Override this in operations that accept secrets via text commands.
     */
    val redactionRules: List<RedactionRule>
        get() = emptyList()

    /**
     * Maximum time this operation may spend in [execute] before being interrupted. When an
     * operation exceeds its timeout, the executing thread is interrupted, the result is discarded,
     * and the chain continues to the next operation.
     */
    val timeout: Duration
        get() = Duration.ofSeconds(30)

    /**
     * Optional rate limit for this operation. When set, OperationService checks a token bucket
     * before calling [execute]. If the bucket is empty, the operation is skipped as if it did not
     * handle the message. Throttle is keyed per provenance URI.
     */
    val throttlePolicy: ThrottlePolicy?
        get() = null

    /**
     * Group name for per-provenance enablement control.
     *
     * Operations sharing a group are enabled/disabled together. When null (the default), the
     * operation cannot be disabled -- use this for admin and infrastructure operations that must
     * always be available. Override with a group name to allow channel-level control via the admin
     * commands (e.g., "karma", "factoid", "urltitle").
     */
    val operationGroup: String?
        get() = null

    /**
     * Resolves the best display name for the message sender: protocol nick, then identity, then
     * fallback
     */
    fun senderName(message: Message<*>): String {
        val nick = message.headers["nick"] as? String
        if (nick != null) return nick
        val provenance = message.headers[Provenance.HEADER] as? Provenance
        return provenance?.user?.displayName ?: provenance?.user?.username ?: "someone"
    }

    /** Quick pre-flight check: is this operation relevant for this message? */
    fun canHandle(message: Message<*>): Boolean = true

    /** Checks whether the message sender has at least the given role */
    fun hasRole(message: Message<*>, role: Role): Boolean {
        val provenance = message.headers[Provenance.HEADER] as? Provenance
        return (provenance?.user?.role ?: Role.GUEST) >= role
    }

    /**
     * Returns an error result if the sender lacks the required role, or null if authorized.
     *
     * Typical usage in handle(): `requireRole(message, Role.ADMIN)?.let { return it }`
     */
    fun requireRole(message: Message<*>, role: Role): OperationResult.Error? {
        if (!hasRole(message, role)) {
            return OperationResult.Error("Insufficient privileges: requires $role")
        }
        return null
    }

    /**
     * Process the message and produce a result.
     *
     * Returns [OperationResult.Success] or [OperationResult.Error] to short-circuit the chain,
     * [Declined] to pass with diagnostic info (logged by OperationService), [Consumed] to stop the
     * chain without publishing to egress, or null to silently pass to the next operation.
     */
    fun execute(message: Message<*>): OperationOutcome?
}
