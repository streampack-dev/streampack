/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.integration

import com.enigmastation.streampack.core.model.Protocol
import com.enigmastation.streampack.core.model.Provenance
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.integration.channel.PublishSubscribeChannel
import org.springframework.integration.channel.QueueChannel
import org.springframework.integration.filter.MessageFilter
import org.springframework.messaging.Message
import org.springframework.messaging.MessageHandler
import org.springframework.messaging.support.MessageBuilder

class ProvenanceMessageSelectorTests {

    @Test
    fun `subscribers receive only messages matching their protocol and serviceId`() {
        val channel = PublishSubscribeChannel()

        // IRC subscriber for "ircservice": should receive only IRC messages to ircservice
        val ircOutput = QueueChannel()
        val ircFilter = MessageFilter(ProvenanceMessageSelector(Protocol.IRC, "ircservice"))
        ircFilter.setOutputChannel(ircOutput)
        channel.subscribe(ircFilter)

        // IRC subscriber for "other-irc": should receive nothing (wrong authority)
        val otherIrcOutput = QueueChannel()
        val otherIrcFilter = MessageFilter(ProvenanceMessageSelector(Protocol.IRC, "other-irc"))
        otherIrcFilter.setOutputChannel(otherIrcOutput)
        channel.subscribe(otherIrcFilter)

        // MAILTO subscriber (no serviceId): should receive the mailto message
        val mailtoOutput = QueueChannel()
        val mailtoFilter = MessageFilter(ProvenanceMessageSelector(Protocol.MAILTO))
        mailtoFilter.setOutputChannel(mailtoOutput)
        channel.subscribe(mailtoFilter)

        // Catch-all subscriber: should receive all messages
        val allMessages = CopyOnWriteArrayList<Message<*>>()
        channel.subscribe(MessageHandler { allMessages.add(it) })

        // DISCORD subscriber: should receive nothing (no discord messages sent)
        val discordOutput = QueueChannel()
        val discordFilter = MessageFilter(ProvenanceMessageSelector(Protocol.DISCORD, "some-guild"))
        discordFilter.setOutputChannel(discordOutput)
        channel.subscribe(discordFilter)

        // Send an IRC message addressed to ircservice
        channel.send(
            MessageBuilder.withPayload("IRC content")
                .setHeader(
                    Provenance.HEADER,
                    Provenance(
                        protocol = Protocol.IRC,
                        serviceId = "ircservice",
                        replyTo = "oftc/#java",
                    ),
                )
                .build()
        )

        // Send a MAILTO message with no authority
        channel.send(
            MessageBuilder.withPayload("Email content")
                .setHeader(
                    Provenance.HEADER,
                    Provenance(protocol = Protocol.MAILTO, replyTo = "user@example.com"),
                )
                .build()
        )

        // IRC "ircservice" got exactly one message with the right payload
        val ircReceived = ircOutput.receive(0)
        assertEquals("IRC content", ircReceived?.payload)
        assertNull(ircOutput.receive(0))

        // IRC "other-irc" got nothing - wrong authority
        assertNull(otherIrcOutput.receive(0))

        // MAILTO got exactly one message
        val mailtoReceived = mailtoOutput.receive(0)
        assertEquals("Email content", mailtoReceived?.payload)
        assertNull(mailtoOutput.receive(0))

        // Catch-all got both messages
        assertEquals(2, allMessages.size)

        // DISCORD got nothing
        assertNull(discordOutput.receive(0))
    }

    @Test
    fun `rejects message with no provenance header`() {
        val selector = ProvenanceMessageSelector(Protocol.IRC, "ircservice")
        val message = MessageBuilder.withPayload("no header").build()
        assertEquals(false, selector.accept(message))
    }

    @Test
    fun `rejects message with wrong type in provenance header`() {
        val selector = ProvenanceMessageSelector(Protocol.IRC, "ircservice")
        val message =
            MessageBuilder.withPayload("wrong type")
                .setHeader(Provenance.HEADER, "not a provenance")
                .build()
        assertEquals(false, selector.accept(message))
    }

    @Test
    fun `selector with no serviceId matches any serviceId for that protocol`() {
        val selector = ProvenanceMessageSelector(Protocol.IRC)
        val message =
            MessageBuilder.withPayload("any irc")
                .setHeader(
                    Provenance.HEADER,
                    Provenance(
                        protocol = Protocol.IRC,
                        serviceId = "whatever-network",
                        replyTo = "#channel",
                    ),
                )
                .build()
        assertEquals(true, selector.accept(message))
    }
}
