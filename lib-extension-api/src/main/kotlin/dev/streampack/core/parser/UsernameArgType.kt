/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.parser

/** Username validator for common cross-protocol account naming constraints. */
data object UsernameArgType : CommandArgType<String> {
    override val syntaxName: String = "username"

    private val usernamePattern = Regex("^[A-Za-z0-9_-]{2,64}$")

    override fun parse(token: String): String? = token.takeIf { usernamePattern.matches(it) }
}
