/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.parser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CommandLexerTests {
    @Test
    fun `split keeps a quoted name with spaces as one argument`() {
        assertEquals(
            listOf("join", "firenet", "Dev Ops"),
            CommandLexer.split("""join firenet "Dev Ops""""),
        )
        assertEquals(
            listOf("autojoin", "firenet", "Dev Ops", "true"),
            CommandLexer.split("autojoin firenet 'Dev Ops' true"),
        )
    }

    @Test
    fun `split leaves unquoted words as they are`() {
        assertEquals(
            listOf("join", "firenet", "#town-square"),
            CommandLexer.split("  join   firenet\t#town-square "),
        )
    }

    @Test
    fun `one kind of quote may hold the other`() {
        assertEquals(listOf("Joe's Place"), CommandLexer.split(""""Joe's Place""""))
        assertEquals(listOf("say \"hi\""), CommandLexer.split("""'say "hi"'"""))
    }

    @Test
    fun `an empty quoted argument is kept`() {
        assertEquals(listOf("signal", "work", ""), CommandLexer.split("""signal work """""))
    }

    @Test
    fun `an unclosed quote is refused rather than guessed at`() {
        val e = assertThrows<UnbalancedQuoteException> { CommandLexer.split("""join "Dev Ops""") }
        assertEquals("""Unclosed " in: join "Dev Ops""", e.message)
        assertThrows<UnbalancedQuoteException> { CommandLexer.split("join Joe's") }
    }

    @Test
    fun `lex stays lenient about an unclosed quote`() {
        assertEquals(listOf("foo", "bar baz"), CommandLexer.lex("""foo "bar baz""").tokens)
    }
}
