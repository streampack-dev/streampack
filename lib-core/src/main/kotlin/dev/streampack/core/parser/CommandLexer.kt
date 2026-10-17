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
 *
 * [lex] forgives an unclosed quote (the rest of the input is one token); [split], for admin
 * commands where a wrong argument changes the wrong thing, refuses it.
 */
object CommandLexer {
    fun lex(raw: String): LexedInput {
        val trimmed = raw.trim()
        val triggered = trimmed.startsWith("!")
        val input = if (triggered) trimmed.drop(1).trimStart() else trimmed
        return LexedInput(
            raw = raw,
            triggered = triggered,
            tokens = tokenize(input, strict = false),
        )
    }

    /**
     * Arguments split as [lex] splits them, with no trigger prefix, keeping an empty quoted
     * argument (`""`); an unclosed quote throws [UnbalancedQuoteException].
     */
    fun split(input: String): List<String> = tokenize(input.trim(), strict = true)

    private fun tokenize(input: String, strict: Boolean): List<String> {
        if (input.isBlank()) return emptyList()

        val tokens = mutableListOf<String>()
        val current = StringBuilder()

        var inSingleQuote = false
        var inDoubleQuote = false
        var escaping = false
        var quoted = false

        fun flush() {
            if (current.isNotEmpty() || (strict && quoted)) {
                tokens += current.toString()
                current.clear()
            }
            quoted = false
        }

        for (ch in input) {
            if (escaping) {
                current.append(ch)
                escaping = false
                continue
            }

            when {
                ch == '\\' && (inSingleQuote || inDoubleQuote) -> escaping = true
                ch == '\'' && !inDoubleQuote -> {
                    inSingleQuote = !inSingleQuote
                    quoted = true
                }
                ch == '"' && !inSingleQuote -> {
                    inDoubleQuote = !inDoubleQuote
                    quoted = true
                }
                ch.isWhitespace() && !inSingleQuote && !inDoubleQuote -> flush()
                else -> current.append(ch)
            }
        }
        if (strict && (inSingleQuote || inDoubleQuote)) {
            throw UnbalancedQuoteException(if (inDoubleQuote) '"' else '\'', input)
        }
        flush()
        return tokens
    }
}

/** An admin command with a quote that is never closed */
class UnbalancedQuoteException(quote: Char, input: String) :
    IllegalArgumentException("Unclosed $quote in: $input")
