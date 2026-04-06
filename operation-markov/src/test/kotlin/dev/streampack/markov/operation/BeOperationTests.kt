/* Joseph B. Ottinger (C)2026 */
package dev.streampack.markov.operation

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.MessageLogService
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class BeOperationTests {

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var messageLogService: MessageLogService

    private fun provenance() =
        Provenance(protocol = Protocol.IRC, serviceId = "testnet", replyTo = "testchan")

    private fun beMessage(text: String) =
        MessageBuilder.withPayload(text).setHeader(Provenance.HEADER, provenance()).build()

    @Test
    fun `be with no history returns no data message`() {
        val result = eventGateway.process(beMessage("be ghostuser"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("No message history"))
    }

    @Test
    fun `be with sufficient history generates attributed output`() {
        val prov = provenance()
        val uri = prov.encode()
        repeat(20) { i ->
            messageLogService.logInbound(uri, "chattyuser", "the quick brown fox number $i")
        }
        repeat(20) { i ->
            messageLogService.logInbound(uri, "chattyuser", "jumps over the lazy dog number $i")
        }

        val result = eventGateway.process(beMessage("be chattyuser"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.startsWith("* channeling chattyuser:"))
    }

    @Test
    fun `be ignores messages from other protocols`() {
        val discordUri =
            Provenance(protocol = Protocol.DISCORD, serviceId = "guild", replyTo = "chan").encode()
        repeat(20) { i ->
            messageLogService.logInbound(discordUri, "crossuser", "discord message number $i here")
        }

        val result = eventGateway.process(beMessage("be crossuser"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("No message history"))
    }

    @Test
    fun `be is case-insensitive on username`() {
        val prov = provenance()
        val uri = prov.encode()
        repeat(20) { i ->
            messageLogService.logInbound(uri, "MixedCase", "the quick brown fox number $i jumps")
        }
        repeat(20) { i ->
            messageLogService.logInbound(uri, "MixedCase", "over the lazy dog number $i today")
        }

        val result = eventGateway.process(beMessage("be mixedcase"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.startsWith("* channeling mixedcase:"))
    }

    @Test
    fun `be with blank username is not handled`() {
        val result = eventGateway.process(beMessage("be "))
        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }

    @Test
    fun `non-be message is not handled`() {
        val result = eventGateway.process(beMessage("karma someone"))
        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }

    @Test
    fun `be ignores outbound messages in corpus`() {
        val prov = provenance()
        val uri = prov.encode()
        repeat(20) { i ->
            messageLogService.logOutbound(uri, "botuser", "response number $i from bot")
        }

        val result = eventGateway.process(beMessage("be botuser"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("No message history"))
    }
}
