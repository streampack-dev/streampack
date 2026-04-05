/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.model

/**
 * Declares which token positions in a text command contain secrets and must be redacted before
 * logging. Operations that accept secrets via text commands override [Operation.redactionRules] to
 * return one or more of these.
 *
 * @param prefix case-insensitive command prefix to match (e.g., "irc connect")
 * @param positions 0-indexed token positions to replace with [REDACTED]
 */
data class RedactionRule(val prefix: String, val positions: Set<Int>)
