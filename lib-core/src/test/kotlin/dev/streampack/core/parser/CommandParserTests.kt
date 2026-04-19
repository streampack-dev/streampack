/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.parser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommandParserTests {
    private val booleanArgType = ChoiceArgType(setOf("true", "false"))

    private fun booleanArg(name: String, helpText: String? = null): CommandArgSpec<String> =
        CommandArgSpec(name, booleanArgType, helpText)

    private fun booleanMatcher(): CommandPatternMatcher =
        CommandPatternMatcher(
            listOf(
                CommandPattern(
                    name = "boolean_xor",
                    literals = listOf("boolean", "xor"),
                    args =
                        listOf(
                            booleanArg("left", "Left boolean operand"),
                            booleanArg("right", "Right boolean operand"),
                        ),
                    summary = "Exclusive-or over two boolean inputs",
                ),
                CommandPattern(
                    name = "boolean_and",
                    literals = listOf("boolean", "and"),
                    args =
                        listOf(
                            booleanArg("left", "Left boolean operand"),
                            booleanArg("right", "Right boolean operand"),
                        ),
                    summary = "Logical and over two boolean inputs",
                ),
                CommandPattern(
                    name = "boolean_or",
                    literals = listOf("boolean", "or"),
                    args =
                        listOf(
                            booleanArg("left", "Left boolean operand"),
                            booleanArg("right", "Right boolean operand"),
                        ),
                    summary = "Logical or over two boolean inputs",
                ),
                CommandPattern(
                    name = "boolean_not",
                    literals = listOf("boolean", "not"),
                    args = listOf(booleanArg("value", "Boolean input to negate")),
                    summary = "Logical not over one boolean input",
                ),
                CommandPattern(
                    name = "boolean_help",
                    literals = listOf("boolean", "help"),
                    summary = "Show boolean command help",
                ),
            )
        )

    private val matcher =
        CommandPatternMatcher(
            listOf(
                CommandPattern(name = "do_this", literals = listOf("do", "this")),
                CommandPattern(
                    name = "do_that",
                    literals = listOf("do", "that"),
                    args =
                        listOf(
                            CommandArgSpec("username", UsernameArgType),
                            CommandArgSpec("content", StringArgType),
                        ),
                ),
            )
        )

    @Test
    fun `lexer keeps quoted whitespace and detects trigger`() {
        val lexed = CommandLexer.lex("""!    foo bar "   baz bletch"     quux""")

        assertTrue(lexed.triggered)
        assertEquals(listOf("foo", "bar", "   baz bletch", "quux"), lexed.tokens)
    }

    @Test
    fun `matcher handles do this without arguments`() {
        val result = matcher.match("!do this")

        assertInstanceOf(CommandMatchResult.Match::class.java, result)
        result as CommandMatchResult.Match
        assertEquals("do_this", result.patternName)
        assertTrue(result.triggered)
        assertTrue(result.captures.isEmpty())
    }

    @Test
    fun `matcher handles do that with username and quoted content`() {
        val result = matcher.match("""!do that dreamreal 'foo bar baz'""")

        assertInstanceOf(CommandMatchResult.Match::class.java, result)
        result as CommandMatchResult.Match
        assertEquals("do_that", result.patternName)
        assertEquals("dreamreal", result.captures["username"])
        assertEquals("foo bar baz", result.captures["content"])
    }

    @Test
    fun `do that with one argument returns missing content`() {
        val result = matcher.match("do that dreamreal")

        assertInstanceOf(CommandMatchResult.MissingArguments::class.java, result)
        result as CommandMatchResult.MissingArguments
        assertEquals("do_that", result.patternName)
        assertEquals(listOf("content"), result.missing)
    }

    @Test
    fun `do that with no arguments returns missing username and content`() {
        val result = matcher.match("do that")

        assertInstanceOf(CommandMatchResult.MissingArguments::class.java, result)
        result as CommandMatchResult.MissingArguments
        assertEquals("do_that", result.patternName)
        assertEquals(listOf("username", "content"), result.missing)
    }

    @Test
    fun `unknown command returns null`() {
        val result = matcher.match("something else entirely")
        assertNull(result)
    }

    @Test
    fun `overloaded patterns prefer exact argument count match`() {
        val overloaded =
            CommandPatternMatcher(
                listOf(
                    CommandPattern(
                        name = "today",
                        literals = listOf("today"),
                        args = listOf(CommandArgSpec("target", StringArgType)),
                    ),
                    CommandPattern(name = "today", literals = listOf("today")),
                )
            )

        val result = overloaded.match("today")
        assertInstanceOf(CommandMatchResult.Match::class.java, result)
        result as CommandMatchResult.Match
        assertTrue(result.captures.isEmpty())
    }

    @Test
    fun `choice argument validates against options`() {
        val choiceMatcher =
            CommandPatternMatcher(
                listOf(
                    CommandPattern(
                        name = "today",
                        literals = listOf("today"),
                        args =
                            listOf(
                                CommandArgSpec(
                                    "target",
                                    ChoiceArgType(setOf("list", "gregorian", "hebrew")),
                                )
                            ),
                    )
                )
            )

        val valid = choiceMatcher.match("today hebrew")
        assertInstanceOf(CommandMatchResult.Match::class.java, valid)

        val invalid = choiceMatcher.match("today not-a-calendar")
        assertInstanceOf(CommandMatchResult.InvalidArgument::class.java, invalid)
    }

    @Test
    fun `positive integer argument rejects zero and negatives`() {
        val intMatcher =
            CommandPatternMatcher(
                listOf(
                    CommandPattern(
                        name = "rfc",
                        literals = listOf("rfc"),
                        args = listOf(CommandArgSpec("identifier", PositiveIntArgType)),
                    )
                )
            )

        assertInstanceOf(CommandMatchResult.Match::class.java, intMatcher.match("rfc 2812"))
        assertInstanceOf(CommandMatchResult.InvalidArgument::class.java, intMatcher.match("rfc 0"))
        assertInstanceOf(CommandMatchResult.InvalidArgument::class.java, intMatcher.match("rfc -5"))
    }

    @Test
    fun `range integer argument enforces bounds`() {
        val rangeMatcher =
            CommandPatternMatcher(
                listOf(
                    CommandPattern(
                        name = "take",
                        literals = listOf("21", "take"),
                        args = listOf(CommandArgSpec("count", IntRangeArgType(1..3))),
                    )
                )
            )

        assertInstanceOf(CommandMatchResult.Match::class.java, rangeMatcher.match("21 take 1"))
        assertInstanceOf(CommandMatchResult.Match::class.java, rangeMatcher.match("21 take 3"))
        assertInstanceOf(
            CommandMatchResult.InvalidArgument::class.java,
            rangeMatcher.match("21 take 0"),
        )
        assertInstanceOf(
            CommandMatchResult.InvalidArgument::class.java,
            rangeMatcher.match("21 take 4"),
        )
    }

    @Test
    fun `http url argument accepts only http and https`() {
        val urlMatcher =
            CommandPatternMatcher(
                listOf(
                    CommandPattern(
                        name = "suggest",
                        literals = listOf("suggest"),
                        args = listOf(CommandArgSpec("url", HttpUrlArgType)),
                    )
                )
            )

        assertInstanceOf(
            CommandMatchResult.Match::class.java,
            urlMatcher.match("suggest https://example.com/post"),
        )
        assertInstanceOf(
            CommandMatchResult.Match::class.java,
            urlMatcher.match("suggest http://example.com/post"),
        )
        assertInstanceOf(
            CommandMatchResult.InvalidArgument::class.java,
            urlMatcher.match("suggest ftp://example.com/post"),
        )
        assertInstanceOf(
            CommandMatchResult.InvalidArgument::class.java,
            urlMatcher.match("suggest file:///tmp/test"),
        )
    }

    @Test
    fun `matcher can render grammar lines for boolean command tree`() {
        assertEquals(
            listOf(
                "boolean xor <left:one-of(false|true)> <right:one-of(false|true)>",
                "boolean and <left:one-of(false|true)> <right:one-of(false|true)>",
                "boolean or <left:one-of(false|true)> <right:one-of(false|true)>",
                "boolean not <value:one-of(false|true)>",
                "boolean help",
            ),
            booleanMatcher().describeGrammar(),
        )
    }

    @Test
    fun `matcher can render help lines with summaries and argument help`() {
        assertEquals(
            listOf(
                "boolean xor <left:one-of(false|true)> <right:one-of(false|true)> -- Exclusive-or over two boolean inputs",
                "  <left:one-of(false|true)>: Left boolean operand",
                "  <right:one-of(false|true)>: Right boolean operand",
                "boolean and <left:one-of(false|true)> <right:one-of(false|true)> -- Logical and over two boolean inputs",
                "  <left:one-of(false|true)>: Left boolean operand",
                "  <right:one-of(false|true)>: Right boolean operand",
                "boolean or <left:one-of(false|true)> <right:one-of(false|true)> -- Logical or over two boolean inputs",
                "  <left:one-of(false|true)>: Left boolean operand",
                "  <right:one-of(false|true)>: Right boolean operand",
                "boolean not <value:one-of(false|true)> -- Logical not over one boolean input",
                "  <value:one-of(false|true)>: Boolean input to negate",
                "boolean help -- Show boolean command help",
            ),
            booleanMatcher().describeHelp(),
        )
    }

    @Test
    fun `boolean binary commands match typed boolean choice arguments`() {
        val cases =
            mapOf(
                "boolean xor true false" to "boolean_xor",
                "boolean and true false" to "boolean_and",
                "boolean or true false" to "boolean_or",
            )

        for ((input, patternName) in cases) {
            val result = booleanMatcher().match(input)

            assertInstanceOf(CommandMatchResult.Match::class.java, result)
            result as CommandMatchResult.Match
            assertEquals(patternName, result.patternName)
            assertEquals("true", result.captures["left"])
            assertEquals("false", result.captures["right"])
        }
    }

    @Test
    fun `boolean not command matches one typed boolean choice argument`() {
        val result = booleanMatcher().match("boolean not false")

        assertInstanceOf(CommandMatchResult.Match::class.java, result)
        result as CommandMatchResult.Match
        assertEquals("boolean_not", result.patternName)
        assertEquals("false", result.captures["value"])
    }
}
