/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.parser

/**
 * Command grammar fragment: fixed leading literals plus typed positional args.
 *
 * Example: `today [calendar]` can be modeled as:
 * - literals = ["today"]
 * - args = [CommandArgSpec("calendar", ChoiceArgType(...))]
 */
data class CommandPattern(
    val name: String,
    val literals: List<String>,
    val args: List<CommandArgSpec<*>> = emptyList(),
    val caseInsensitiveLiterals: Boolean = true,
)
