/* Joseph B. Ottinger (C)2026 */
package dev.streampack.irc.service

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.LoggingRequest
import dev.streampack.core.model.MessageKind
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.ChannelControlService
import dev.streampack.core.service.ProvenanceStateService
import dev.streampack.core.service.UserResolutionService
import dev.streampack.irc.repository.IrcChannelRepository
import dev.streampack.irc.repository.IrcNetworkRepository
import java.util.Optional
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.kitteh.irc.client.library.Client
import org.kitteh.irc.client.library.element.Channel
import org.kitteh.irc.client.library.element.User
import org.kitteh.irc.client.library.event.channel.ChannelJoinEvent
import org.kitteh.irc.client.library.event.channel.ChannelPartEvent
import org.kitteh.irc.client.library.event.channel.ChannelTopicEvent
import org.kitteh.irc.client.library.event.user.UserNickChangeEvent
import org.kitteh.irc.client.library.event.user.UserQuitEvent
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.springframework.messaging.Message

/**
 * Each IRC event is logged with its kind, and a quit or nick change once in each channel the bot
 * shares with the person, never under the pseudo-channel `*` (#174).
 */
class IrcEventKindTests {

    /** What the adapter sends to ingress; events are sent from virtual threads */
    private class RecordingGateway : EventGateway {
        val sent = CopyOnWriteArrayList<Message<*>>()

        override fun process(message: Message<*>): OperationResult {
            sent += message
            return OperationResult.NotHandled
        }

        override fun send(message: Message<*>) {
            sent += message
        }

        fun awaitCount(count: Int): List<Message<*>> {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (sent.size < count && System.nanoTime() < deadline) Thread.onSpinWait()
            Thread.sleep(50) // anything extra would have arrived by now
            return sent.toList()
        }
    }

    private val gateway = RecordingGateway()
    private val client: Client =
        mock(Client::class.java, Mockito.RETURNS_DEEP_STUBS).also {
            Mockito.`when`(it.nick).thenReturn("nevet")
        }
    private val adapter =
        IrcAdapter(
            networkName = "kindnet",
            eventGateway = gateway,
            userResolutionService = mock(UserResolutionService::class.java),
            channelControlService = mock(ChannelControlService::class.java),
            stateService = mock(ProvenanceStateService::class.java),
            networkRepository = mock(IrcNetworkRepository::class.java),
            channelRepository = mock(IrcChannelRepository::class.java),
            client = client,
            initialSignalCharacter = "!",
            identity = "test",
        )

    private fun user(nick: String, vararg channels: String): User {
        val user = mock(User::class.java)
        Mockito.`when`(user.nick).thenReturn(nick)
        Mockito.`when`(user.channels).thenReturn(channels.toSet())
        return user
    }

    private fun channel(name: String): Channel {
        val channel = mock(Channel::class.java)
        Mockito.`when`(channel.name).thenReturn(name)
        return channel
    }

    /** Each logged event: (channel, kind, text, actor) */
    private fun logged(count: Int): List<List<Any?>> =
        gateway.awaitCount(count).map {
            val provenance = it.headers[Provenance.HEADER] as Provenance
            val request = it.payload as LoggingRequest
            listOf(provenance.replyTo, request.kind, request.content, it.headers["nick"])
        }

    @Test
    fun `a quit is logged in every channel the person shared with the bot, never under star`() {
        val event = mock(UserQuitEvent::class.java)
        val ada = user("ada", "#kotlin", "#java")
        Mockito.`when`(event.actor).thenReturn(ada)
        Mockito.`when`(event.message).thenReturn("Ping timeout")

        adapter.onUserQuit(event)

        val lines = logged(2)
        assertEquals(
            listOf(
                listOf("#java", MessageKind.QUIT, "* ada quit (Ping timeout)", "ada"),
                listOf("#kotlin", MessageKind.QUIT, "* ada quit (Ping timeout)", "ada"),
            ),
            lines.sortedBy { it[0] as String },
        )
        assertTrue(lines.none { it[0] == "*" })
    }

    @Test
    fun `a quit by someone in no shared channel is logged nowhere`() {
        val event = mock(UserQuitEvent::class.java)
        val ghost = user("ghost")
        Mockito.`when`(event.actor).thenReturn(ghost)
        Mockito.`when`(event.message).thenReturn("")

        adapter.onUserQuit(event)

        assertTrue(logged(0).isEmpty())
    }

    @Test
    fun `a nick change is logged in every shared channel, never under star`() {
        val event = mock(UserNickChangeEvent::class.java)
        val ada = user("ada", "#java", "##lisp")
        Mockito.`when`(event.actor).thenReturn(ada)
        val renamed = user("ada_", "#java", "##lisp")
        Mockito.`when`(event.newUser).thenReturn(renamed)

        adapter.onNickChange(event)

        assertEquals(
            listOf(
                listOf("##lisp", MessageKind.NICK, "* ada is now known as ada_", "ada"),
                listOf("#java", MessageKind.NICK, "* ada is now known as ada_", "ada"),
            ),
            logged(2).sortedBy { it[0] as String },
        )
    }

    @Test
    fun `joins, parts and topics are logged in their channel with their kind`() {
        val java = channel("#java")
        val join = mock(ChannelJoinEvent::class.java)
        Mockito.`when`(join.channel).thenReturn(java)
        val ada = user("ada", "#java")
        Mockito.`when`(join.actor).thenReturn(ada)
        val part = mock(ChannelPartEvent::class.java)
        Mockito.`when`(part.channel).thenReturn(java)
        val bob = user("bob", "#java")
        Mockito.`when`(part.actor).thenReturn(bob)
        Mockito.`when`(part.message).thenReturn("lunch")
        val topic = mock(ChannelTopicEvent::class.java, Mockito.RETURNS_DEEP_STUBS)
        Mockito.`when`(topic.channel).thenReturn(java)
        Mockito.`when`(topic.newTopic.setter).thenReturn(Optional.empty())
        Mockito.`when`(topic.newTopic.value).thenReturn(Optional.of("Java"))

        adapter.onUserJoin(join)
        logged(1)
        adapter.onUserPart(part)
        logged(2)
        adapter.onChannelTopic(topic)

        assertEquals(
            listOf(
                listOf("#java", MessageKind.JOIN, "* ada joined #java", "ada"),
                listOf("#java", MessageKind.PART, "* bob left #java (lunch)", "bob"),
                listOf("#java", MessageKind.TOPIC, "* someone changed the topic to: Java", null),
            ),
            logged(3),
        )
    }

    @Test
    fun `shared channels are each channel once, without star`() {
        assertEquals(
            listOf("#a", "#b"),
            IrcAdapter.sharedChannels(setOf("#b", "*", ""), setOf("#a", "#b")),
        )
        assertTrue(IrcAdapter.sharedChannels(emptySet()).isEmpty())
    }
}
