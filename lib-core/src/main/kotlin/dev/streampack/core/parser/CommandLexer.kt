/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.parser

/**
 * Shared lexer for command-like input.
 *
 * Behavior:
 * - trims surrounding whitespace
 * - captures optional trigger prefix (`!`) into [LexedInput.triggered]
 * - tokenizes on unquoted whitespace
 * - preserves quoted segments as single tokens with quote characters removed
 * - supports escaped characters inside quotes via `\\`
 */
object CommandLexer {
    fun lex(raw: String): LexedInput {
        val trimmed = raw.trim()
        val triggered = trimmed.startsWith("!")
        val input = if (triggered) trimmed.drop(1).trimStart() else trimmed
        return LexedInput(raw = raw, triggered = triggered, tokens = tokenize(input))
    }

    private fun tokenize(input: String): List<String> {
        if (input.isBlank()) return emptyList()

        val tokens = mutableListOf<String>()
        val current = StringBuilder()

        var inSingleQuote = false
        var inDoubleQuote = false
        var escaping = false

        fun flush() {
            if (current.isNotEmpty()) {
                tokens += current.toString()
                current.clear()
            }
        }

        for (ch in input) {
            if (escaping) {
                current.append(ch)
                escaping = false
                continue
            }

            when {
                ch == '\\' && (inSingleQuote || inDoubleQuote) -> escaping = true
                ch == '\'' && !inDoubleQuote -> inSingleQuote = !inSingleQuote
                ch == '"' && !inSingleQuote -> inDoubleQuote = !inDoubleQuote
                ch.isWhitespace() && !inSingleQuote && !inDoubleQuote -> flush()
                else -> current.append(ch)
            }
        }
        flush()
        return tokens
    }
}
