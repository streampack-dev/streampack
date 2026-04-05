/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.parser

/**
 * Argument type constrained to a fixed set of valid options.
 *
 * Returns the canonical option spelling from [options], allowing case-insensitive matching when
 * enabled.
 */
data class ChoiceArgType(
    private val options: Set<String>,
    private val caseInsensitive: Boolean = true,
) : CommandArgType<String> {

    private val normalizedToCanonical: Map<String, String> =
        if (caseInsensitive) options.associateBy { it.lowercase() } else emptyMap()

    val validOptions: List<String> = options.toList().sorted()

    override fun parse(token: String): String? {
        return if (caseInsensitive) {
            normalizedToCanonical[token.lowercase()]
        } else {
            token.takeIf { it in options }
        }
    }
}
