/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.parser

/**
 * Matches command input against a list of [CommandPattern] definitions.
 *
 * Matching algorithm:
 * - evaluate all patterns whose literal prefixes match
 * - return immediately on a full [CommandMatchResult.Match]
 * - otherwise return the strongest failure signal among literal matches
 *   ([CommandMatchResult.InvalidArgument] > [CommandMatchResult.MissingArguments] > [CommandMatchResult.TooManyArguments])
 * - return null if no literal prefix matched
 *
 * The matcher can also render its own grammar/help lines via [describeGrammar] and [describeHelp].
 * This keeps parser-driven command documentation close to the command definitions themselves.
 */
class CommandPatternMatcher(private val patterns: List<CommandPattern>) {
    fun match(raw: String): CommandMatchResult? {
        val lexed = CommandLexer.lex(raw)
        if (lexed.tokens.isEmpty()) return null

        var fallback: CommandMatchResult? = null
        var matchedLiteralPrefix = false

        for (pattern in patterns) {
            if (!matchesLiterals(pattern, lexed.tokens)) continue
            matchedLiteralPrefix = true
            val provided = lexed.tokens.drop(pattern.literals.size)
            val expected = pattern.args.size

            if (provided.size < expected) {
                fallback =
                    preferFailure(
                        fallback,
                        CommandMatchResult.MissingArguments(
                            patternName = pattern.name,
                            missing = pattern.args.drop(provided.size).map { it.name },
                            providedCount = provided.size,
                            requiredCount = expected,
                        ),
                    )
                continue
            }
            if (provided.size > expected) {
                fallback =
                    preferFailure(
                        fallback,
                        CommandMatchResult.TooManyArguments(
                            patternName = pattern.name,
                            providedCount = provided.size,
                            expectedCount = expected,
                        ),
                    )
                continue
            }

            val captures = mutableMapOf<String, Any>()
            var invalidArg = false
            for (index in pattern.args.indices) {
                @Suppress("UNCHECKED_CAST") val arg = pattern.args[index] as CommandArgSpec<Any>
                val token = provided[index]
                val parsed = arg.type.parse(token)
                if (parsed == null) {
                    invalidArg = true
                    fallback =
                        preferFailure(
                            fallback,
                            CommandMatchResult.InvalidArgument(
                                patternName = pattern.name,
                                argumentName = arg.name,
                                token = token,
                            ),
                        )
                    break
                }
                captures[arg.name] = parsed
            }
            if (invalidArg) continue

            return CommandMatchResult.Match(
                patternName = pattern.name,
                captures = captures,
                triggered = lexed.triggered,
                tokens = lexed.tokens,
            )
        }

        return if (matchedLiteralPrefix) fallback else null
    }

    /**
     * Renders one BNF-ish grammar line per configured pattern.
     *
     * Example output:
     *
     * `boolean xor <left:one-of(false|true)> <right:one-of(false|true)>`
     */
    fun describeGrammar(): List<String> = patterns.map { it.renderGrammar() }

    /**
     * Renders grammar plus any available summaries and argument help text.
     *
     * The output format is intentionally plain list-of-strings so callers can publish it directly
     * in chat, logs, or docs without depending on a richer presentation model.
     */
    fun describeHelp(): List<String> {
        val lines = mutableListOf<String>()
        for (pattern in patterns) {
            val summarySuffix = pattern.summary?.let { " -- $it" } ?: ""
            lines += pattern.renderGrammar() + summarySuffix
            for (arg in pattern.args) {
                arg.helpText?.let { lines += "  ${arg.renderGrammar()}: $it" }
            }
        }
        return lines
    }

    private fun matchesLiterals(pattern: CommandPattern, tokens: List<String>): Boolean {
        if (tokens.size < pattern.literals.size) return false
        val prefixes = tokens.take(pattern.literals.size)
        return if (pattern.caseInsensitiveLiterals) {
            prefixes.zip(pattern.literals).all { (actual, expected) ->
                actual.equals(expected, ignoreCase = true)
            }
        } else {
            prefixes == pattern.literals
        }
    }

    private fun preferFailure(
        existing: CommandMatchResult?,
        candidate: CommandMatchResult,
    ): CommandMatchResult {
        if (existing == null) return candidate

        fun rank(value: CommandMatchResult): Int =
            when (value) {
                is CommandMatchResult.InvalidArgument -> 3
                is CommandMatchResult.MissingArguments -> 2
                is CommandMatchResult.TooManyArguments -> 1
                is CommandMatchResult.Match -> 4
            }

        val existingRank = rank(existing)
        val candidateRank = rank(candidate)
        if (candidateRank != existingRank)
            return if (candidateRank > existingRank) candidate else existing

        if (
            existing is CommandMatchResult.TooManyArguments &&
                candidate is CommandMatchResult.TooManyArguments
        ) {
            return if (candidate.expectedCount > existing.expectedCount) candidate else existing
        }
        if (
            existing is CommandMatchResult.MissingArguments &&
                candidate is CommandMatchResult.MissingArguments
        ) {
            return if (candidate.requiredCount < existing.requiredCount) candidate else existing
        }
        return existing
    }
}
