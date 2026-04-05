/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.parser

/**
 * Strategy interface for validating and converting a token into a typed argument.
 *
 * Returning null signals that the token is invalid for this argument specification.
 */
sealed interface CommandArgType<T> {
    fun parse(token: String): T?
}
