/* Joseph B. Ottinger (C)2026 */
package dev.streampack.console

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.UserPrincipal
import java.io.BufferedReader
import java.io.PrintStream
import org.springframework.messaging.support.MessageBuilder

/**
 * The console's read loop: each line read is sent into the event system, addressed, as the
 * super-admin. Input is fire-and-forget; output arrives via [ConsoleEgressSubscriber].
 */
class ConsoleSession(private val eventGateway: EventGateway) {

    /** How a session ended, and what to tell the operator about it. */
    enum class Ending(val explanation: String) {
        NO_SUPER_ADMIN(
            "No super-admin account, so the console can't start. Set ADMIN_EMAIL and restart: " +
                "the account is created at startup when none exists.",
        ),
        NO_INPUT(
            "The console's input is closed, so the console has ended. Under Docker Compose, give " +
                "the service stdin_open: true and tty: true, then attach with docker attach " +
                "(detach with Ctrl-P Ctrl-Q; Ctrl-C stops the server). Set CONSOLE_ENABLED=false " +
                "to run without it.",
        ),
        INPUT_CLOSED(
            "The console's input closed, so the console has ended. The server keeps running.",
        ),
        EXIT("Console ended. The server keeps running; the console is back on the next restart."),
    }

    /** Reads [input] until it closes or says exit or quit, prompting on [out]. */
    fun run(principal: UserPrincipal?, input: BufferedReader, out: PrintStream): Ending {
        if (principal == null) return Ending.NO_SUPER_ADMIN

        val provenance =
            Provenance(
                protocol = Protocol.CONSOLE,
                serviceId = "",
                replyTo = "local",
                user = principal,
            )
        var read = false
        while (true) {
            out.print("> ")
            out.flush()
            val line = input.readLine() ?: return if (read) Ending.INPUT_CLOSED else Ending.NO_INPUT
            read = true
            if (line.isBlank()) continue
            if (line == "exit" || line == "quit") return Ending.EXIT

            eventGateway.send(
                MessageBuilder.withPayload(line)
                    .setHeader(Provenance.HEADER, provenance)
                    .setHeader(Provenance.ADDRESSED, true)
                    .build(),
            )
        }
    }
}
