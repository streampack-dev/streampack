/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.parser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommandParserTests {

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
}
