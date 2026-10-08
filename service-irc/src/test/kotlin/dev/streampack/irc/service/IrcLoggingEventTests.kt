/* Joseph B. Ottinger (C)2026 */
package dev.streampack.irc.service

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.MessageKind
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.MessageLogService
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/** Channel events are logged as from the nick they're about, not "unknown" (#124). */
@SpringBootTest
class IrcLoggingEventTests {

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var messageLogService: MessageLogService

    private fun logged(channel: String): List<Pair<String, String>> {
        val uri =
            Provenance(protocol = Protocol.IRC, serviceId = "evtnet", replyTo = channel).encode()
        return messageLogService
            .findLatestMessages(
                uri,
                Instant.now().minusSeconds(60),
                Instant.now().plusSeconds(1),
                10,
            )
            .map { it.sender to it.content }
    }

    @Test
    fun `an event is logged as from its actor`() {
        val channel = "#evt-${UUID.randomUUID().toString().take(8)}"

        eventGateway.process(
            IrcAdapter.loggingEvent(
                "evtnet",
                "nevet",
                channel,
                "* pebble joined $channel",
                "pebble",
            )
        )

        assertEquals(listOf("pebble" to "* pebble joined $channel"), logged(channel))
    }

    @Test
    fun `an event with no actor is logged as before`() {
        val channel = "#evt-${UUID.randomUUID().toString().take(8)}"

        eventGateway.process(
            IrcAdapter.loggingEvent(
                "evtnet",
                "nevet",
                channel,
                "* someone changed the topic to: hello",
                null,
            )
        )

        assertEquals(listOf("unknown" to "* someone changed the topic to: hello"), logged(channel))
    }

    @Test
    fun `an event is logged with its kind, in its channel`() {
        val channel = "#evt-${UUID.randomUUID().toString().take(8)}"
        val before = Instant.now().minusSeconds(1)

        eventGateway.process(
            IrcAdapter.loggingEvent(
                "evtnet",
                "nevet",
                channel,
                "* pebble quit (bye)",
                "pebble",
                MessageKind.QUIT,
            )
        )

        val uri =
            Provenance(protocol = Protocol.IRC, serviceId = "evtnet", replyTo = channel).encode()
        val lines = messageLogService.findMessages(uri, before, Instant.now().plusSeconds(1), 10)
        assertEquals(
            listOf(MessageKind.QUIT to "* pebble quit (bye)"),
            lines.map { it.kind to it.content },
        )
    }
}
