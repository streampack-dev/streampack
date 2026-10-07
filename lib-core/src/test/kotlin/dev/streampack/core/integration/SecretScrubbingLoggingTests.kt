/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.integration

import db.migration.V62__scrub_secrets_from_message_log
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.repository.MessageLogRepository
import dev.streampack.core.service.Operation
import dev.streampack.core.service.SecretScrubberTests
import dev.streampack.core.service.SenderNotifier
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.messaging.Message
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

/** Secrets are scrubbed from what's logged, both ways, and the sender is told privately (#148) */
@SpringBootTest
@Transactional
class SecretScrubbingLoggingTests {

    data class Notice(val provenance: Provenance, val senderId: String, val text: String)

    @TestConfiguration
    class ScrubbingTestConfig {
        val notices = CopyOnWriteArrayList<Notice>()

        @Bean
        fun recordingIrcNotifier() =
            object : SenderNotifier {
                override val protocol = Protocol.IRC

                override fun notifySender(
                    provenance: Provenance,
                    senderId: String,
                    text: String,
                ): Boolean {
                    notices += Notice(provenance, senderId, text)
                    return true
                }
            }

        @Bean
        fun scrubbingTestRepeatOperation() =
            object : Operation {
                override val priority = 10

                override fun canHandle(message: Message<*>): Boolean =
                    (message.payload as? String)?.startsWith("repeat ") == true

                override fun execute(message: Message<*>): OperationOutcome =
                    OperationResult.Success((message.payload as String).removePrefix("repeat "))
            }
    }

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var config: ScrubbingTestConfig
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var messageLogRepository: MessageLogRepository

    private val token = SecretScrubberTests.github()

    private fun channel() =
        Provenance(
            protocol = Protocol.IRC,
            serviceId = "scrub-${System.nanoTime()}",
            replyTo = "#java",
        )

    private fun say(provenance: Provenance, text: String, nick: String) {
        eventGateway.process(
            MessageBuilder.withPayload(text)
                .setHeader(Provenance.HEADER, provenance)
                .setHeader("nick", nick)
                .setHeader(Provenance.SENDER_ID, nick)
                .build()
        )
    }

    private fun logged(provenance: Provenance, direction: String): List<String?> {
        messageLogRepository.flush()
        return jdbc.queryForList(
            "SELECT content FROM message_log WHERE provenance_uri = ? AND direction = ? " +
                "ORDER BY timestamp",
            String::class.java,
            provenance.encode(),
            direction,
        )
    }

    private fun noticesTo(senderId: String, atLeast: Int = 0): List<Notice> {
        // Notices are delivered off the logging thread
        val deadline = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < deadline) {
            if (config.notices.count { it.senderId == senderId } >= atLeast && atLeast > 0) break
            Thread.sleep(25)
        }
        return config.notices.filter { it.senderId == senderId }
    }

    @Test
    fun `a token is logged scrubbed and the sender gets one private note`() {
        val provenance = channel()
        val nick = "alice${System.nanoTime()}"
        say(provenance, "oops $token", nick)
        say(provenance, "and again $token", nick)

        assertEquals(
            listOf("oops [REDACTED:github-token]", "and again [REDACTED:github-token]"),
            logged(provenance, "INBOUND"),
        )
        val notices = noticesTo(nick, atLeast = 1)
        assertEquals(1, notices.size)
        assertEquals(
            "I've removed what looked like a GitHub token from the log of #java. " +
                "If it was real, revoke it now: it was visible in the channel.",
            notices.single().text,
        )
        // To the sender, on the network the message came from; never to the channel
        assertEquals(provenance.serviceId, notices.single().provenance.serviceId)
        assertTrue(config.notices.none { it.senderId == "#java" })
    }

    @Test
    fun `a direct message is scrubbed, and nobody is told`() {
        val nick = "bob${System.nanoTime()}"
        val provenance =
            Provenance(
                protocol = Protocol.IRC,
                serviceId = "scrub-${System.nanoTime()}",
                replyTo = nick,
            )
        say(provenance, "here: $token", nick)

        assertEquals(listOf("here: [REDACTED:github-token]"), logged(provenance, "INBOUND"))
        Thread.sleep(200)
        assertEquals(emptyList<Notice>(), noticesTo(nick))
    }

    @Test
    fun `a clean message is logged as it was, and nobody is told`() {
        val provenance = channel()
        val nick = "carol${System.nanoTime()}"
        say(provenance, "what's your password policy?", nick)

        assertEquals(listOf("what's your password policy?"), logged(provenance, "INBOUND"))
        Thread.sleep(200)
        assertEquals(emptyList<Notice>(), noticesTo(nick))
    }

    @Test
    fun `the bot's own reply is scrubbed too`() {
        val provenance = channel()
        say(provenance, "repeat $token", "dave${System.nanoTime()}")

        assertEquals(listOf("[REDACTED:github-token]"), logged(provenance, "OUTBOUND"))
    }

    @Test
    fun `the cleanup migration rewrites rows that already hold secrets, direct ones included`() {
        val uri = channel().encode()
        fun insert(content: String, direct: Boolean) =
            jdbc.update(
                "INSERT INTO message_log (id, provenance_uri, direction, sender, content, " +
                    "timestamp, direct) VALUES (gen_random_uuid(), ?, 'INBOUND', 'x', ?, now(), ?)",
                uri,
                content,
                direct,
            )
        insert("old $token", false)
        insert("dm $token", true)
        insert("nothing to see", false)

        val changed =
            jdbc.execute(ConnectionCallback { V62__scrub_secrets_from_message_log.scrub(it) })!!

        assertTrue(changed >= 2)
        assertEquals(
            setOf("old [REDACTED:github-token]", "dm [REDACTED:github-token]", "nothing to see"),
            jdbc
                .queryForList(
                    "SELECT content FROM message_log WHERE provenance_uri = ?",
                    String::class.java,
                    uri,
                )
                .toSet(),
        )
    }
}
