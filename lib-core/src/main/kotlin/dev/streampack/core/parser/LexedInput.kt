/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.parser

/**
 * Result of tokenizing a raw command payload.
 *
 * `triggered` indicates whether input started with `!`. `tokens` are split on unquoted whitespace
 * with quote wrappers removed.
 */
data class LexedInput(val raw: String, val triggered: Boolean, val tokens: List<String>)
