/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.parser

/** Parses an integer constrained to the inclusive [range]. */
data class IntRangeArgType(private val range: IntRange) : CommandArgType<Int> {
    override val syntaxName: String = "int[${range.first}..${range.last}]"

    override fun parse(token: String): Int? = token.toIntOrNull()?.takeIf { it in range }
}
