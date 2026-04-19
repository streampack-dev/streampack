/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.parser

/**
 * Named argument slot in a [CommandPattern].
 *
 * `name` is used as the capture key in [CommandMatchResult.Match.captures]. `helpText` is optional
 * human-facing documentation for grammar rendering and help output.
 */
data class CommandArgSpec<T>(
    val name: String,
    val type: CommandArgType<T>,
    val helpText: String? = null,
) {
    fun renderGrammar(): String = "<$name:${type.syntaxName}>"
}
