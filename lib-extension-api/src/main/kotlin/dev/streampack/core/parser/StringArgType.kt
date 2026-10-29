/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.parser

/** Pass-through argument type that accepts any single token. */
data object StringArgType : CommandArgType<String> {
    override val syntaxName: String = "string"

    override fun parse(token: String): String = token
}
