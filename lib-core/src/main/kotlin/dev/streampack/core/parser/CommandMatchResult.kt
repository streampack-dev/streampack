/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.parser

/** Output contract from [CommandPatternMatcher.match]. */
sealed interface CommandMatchResult {
    /** Full match with typed captures. */
    data class Match(
        val patternName: String,
        val captures: Map<String, Any>,
        val triggered: Boolean,
        val tokens: List<String>,
    ) : CommandMatchResult

    /** Command literal matched but not enough args were provided. */
    data class MissingArguments(
        val patternName: String,
        val missing: List<String>,
        val providedCount: Int,
        val requiredCount: Int,
    ) : CommandMatchResult

    /** Command literal matched but too many args were provided. */
    data class TooManyArguments(
        val patternName: String,
        val providedCount: Int,
        val expectedCount: Int,
    ) : CommandMatchResult

    /** Command literal matched but one arg failed type validation. */
    data class InvalidArgument(
        val patternName: String,
        val argumentName: String,
        val token: String,
    ) : CommandMatchResult
}
