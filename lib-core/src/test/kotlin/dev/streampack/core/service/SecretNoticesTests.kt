/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SecretNoticesTests {

    private val sent = mutableListOf<Triple<String?, String, String>>()

    private val ircNotifier =
        object : SenderNotifier {
            override val protocol = Protocol.IRC

            override fun notifySender(provenance: Provenance, senderId: String, text: String) =
                sent.add(Triple(provenance.serviceId, senderId, text))
        }

    private val slackNames =
        object : ChannelNameProvider {
            override val protocol = Protocol.SLACK

            override fun name(provenance: Provenance) =
                "jvm".takeIf { provenance.replyTo == "C0JVM" }
        }

    private var now = Instant.parse("2026-10-07T12:00:00Z")
    private val clock =
        object : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: java.time.ZoneId?) = this

            override fun instant() = now
        }

    private val notices =
        SecretNotices(
            listOf(ircNotifier),
            listOf(slackNames),
            SecretScrubbingProperties(noticeInterval = Duration.ofMinutes(5)),
            clock,
            Executor { it.run() },
        )

    private val github = SecretScrubber.DEFAULT_PATTERNS.first { it.kind == "github-token" }
    private val aws = SecretScrubber.DEFAULT_PATTERNS.first { it.kind == "aws-access-key" }
    private val libera =
        Provenance(protocol = Protocol.IRC, serviceId = "libera", replyTo = "#java")

    @Test
    fun `a person is told at most once per interval`() {
        assertTrue(notices.notify(libera, "alice", listOf(github)))
        now = now.plus(Duration.ofMinutes(4))
        assertFalse(notices.notify(libera, "alice", listOf(github)))
        assertTrue(notices.notify(libera, "bob", listOf(github)))
        now = now.plus(Duration.ofMinutes(2))
        assertTrue(notices.notify(libera, "alice", listOf(github)))
        assertEquals(listOf("alice", "bob", "alice"), sent.map { it.second })
        assertEquals("libera", sent.first().first)
    }

    @Test
    fun `the same nick on another network is someone else`() {
        assertTrue(notices.notify(libera, "alice", listOf(github)))
        assertTrue(notices.notify(libera.copy(serviceId = "oftc"), "alice", listOf(github)))
    }

    @Test
    fun `a protocol with no notifier is not told anything`() {
        val slack = Provenance(protocol = Protocol.SLACK, serviceId = "work", replyTo = "C0JVM")
        assertFalse(notices.notify(slack, "U123", listOf(github)))
        assertEquals(emptyList<Any>(), sent)
    }

    @Test
    fun `the note names every kind and the channel`() {
        notices.notify(libera, "alice", listOf(github, aws))
        assertEquals(
            "I've removed what looked like a GitHub token and an AWS access key from the log " +
                "of #java. If it was real, revoke it now: it was visible in the channel.",
            sent.single().third,
        )
    }

    @Test
    fun `channels are named as people know them`() {
        assertEquals("#java", notices.channelLabel(libera))
        assertEquals(
            "#jvm",
            notices.channelLabel(
                Provenance(protocol = Protocol.SLACK, serviceId = "w", replyTo = "C0JVM")
            ),
        )
        assertEquals(
            "#general",
            notices.channelLabel(
                Provenance(
                    protocol = Protocol.DISCORD,
                    serviceId = "1",
                    replyTo = "123/guild/#general",
                    metadata = mapOf("channelName" to "general"),
                )
            ),
        )
        assertEquals(
            "the channel",
            notices.channelLabel(
                Provenance(protocol = Protocol.SLACK, serviceId = "w", replyTo = "C0X")
            ),
        )
    }
}
