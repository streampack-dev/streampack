/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.parser

/** Parses strictly positive base-10 integers (`1..Int.MAX_VALUE`). */
data object PositiveIntArgType : CommandArgType<Int> {
    override val syntaxName: String = "positive-int"

    override fun parse(token: String): Int? {
        val value = token.toIntOrNull() ?: return null
        return value.takeIf { it > 0 }
    }
}
