/* Joseph B. Ottinger (C)2026 */
package dev.streampack.console

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.StringReader
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.messaging.Message

/** The console's read loop, without Spring: what it sends, and how it says it ended (#209). */
class ConsoleSessionTests {
    private val admin = UserPrincipal(UUID.randomUUID(), "admin", "Admin", Role.SUPER_ADMIN)

    private class Recording : EventGateway {
        val sent = mutableListOf<Message<*>>()

        override fun send(message: Message<*>) {
            sent += message
        }

        override fun process(message: Message<*>): OperationResult =
            throw UnsupportedOperationException()
    }

    private fun run(
        input: String,
        principal: UserPrincipal? = admin,
    ): Pair<ConsoleSession.Ending, Recording> {
        val gateway = Recording()
        val out = PrintStream(ByteArrayOutputStream())
        val ending =
            ConsoleSession(gateway).run(principal, BufferedReader(StringReader(input)), out)
        return ending to gateway
    }

    @Test
    fun `sends each line, addressed, as the super-admin on the console`() {
        val (ending, gateway) = run("karma foo\n\n  \nfactoid bar\n")

        assertEquals(ConsoleSession.Ending.INPUT_CLOSED, ending)
        assertEquals(listOf("karma foo", "factoid bar"), gateway.sent.map { it.payload })
        val provenance = gateway.sent.first().headers[Provenance.HEADER] as Provenance
        assertEquals(Protocol.CONSOLE, provenance.protocol)
        assertEquals(admin, provenance.user)
        assertEquals(true, gateway.sent.first().headers[Provenance.ADDRESSED])
    }

    @Test
    fun `ends on exit or quit, sending neither`() {
        for (word in listOf("exit", "quit")) {
            val (ending, gateway) = run("karma foo\n$word\nkarma bar\n")
            assertEquals(ConsoleSession.Ending.EXIT, ending)
            assertEquals(listOf("karma foo"), gateway.sent.map { it.payload })
        }
    }

    @Test
    fun `tells input closed from the start apart from input that closes later`() {
        assertEquals(ConsoleSession.Ending.NO_INPUT, run("").first)
        assertEquals(ConsoleSession.Ending.INPUT_CLOSED, run("karma foo\n").first)
    }

    @Test
    fun `doesn't start without a super-admin`() {
        val (ending, gateway) = run("karma foo\n", principal = null)
        assertEquals(ConsoleSession.Ending.NO_SUPER_ADMIN, ending)
        assertTrue(gateway.sent.isEmpty())
    }

    @Test
    fun `says what to do for each ending`() {
        assertTrue(ConsoleSession.Ending.NO_SUPER_ADMIN.explanation.contains("ADMIN_EMAIL"))
        assertTrue(ConsoleSession.Ending.NO_INPUT.explanation.contains("stdin_open"))
        assertTrue(ConsoleSession.Ending.NO_INPUT.explanation.contains("tty"))
        assertTrue(ConsoleSession.Ending.EXIT.explanation.contains("keeps running"))
    }
}
