/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.parser

/**
 * Strategy interface for validating and converting a token into a typed argument.
 *
 * Returning null signals that the token is invalid for this argument specification.
 *
 * [syntaxName] is the parser's human-readable name for this argument type. It is used when
 * rendering grammar/help output from [CommandPatternMatcher].
 */
sealed interface CommandArgType<T> {
    val syntaxName: String

    fun parse(token: String): T?
}
