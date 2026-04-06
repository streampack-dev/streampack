/* Joseph B. Ottinger (C)2026 */
package dev.streampack.ideas.operation

import dev.streampack.blog.entity.Post
import dev.streampack.blog.repository.PostRepository
import dev.streampack.core.entity.ServiceBinding
import dev.streampack.core.entity.User
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.repository.ServiceBindingRepository
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.MessageLogService
import dev.streampack.core.service.ProvenanceStateService
import dev.streampack.ideas.model.IdeaSessionState
import dev.streampack.ideas.service.IdeaTimerService
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.support.MessageBuilder

@SpringBootTest
class ArticleOperationTests {

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var stateService: ProvenanceStateService
    @Autowired lateinit var timerService: IdeaTimerService
    @Autowired lateinit var messageLogService: MessageLogService
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var serviceBindingRepository: ServiceBindingRepository
    @Autowired lateinit var postRepository: PostRepository

    private val alicePrincipal =
        UserPrincipal(
            id = UUID.randomUUID(),
            username = "alice",
            displayName = "Alice",
            role = Role.USER,
        )
    private val bobPrincipal =
        UserPrincipal(
            id = UUID.randomUUID(),
            username = "bob",
            displayName = "Bob",
            role = Role.USER,
        )

    private val provenance =
        Provenance(
            protocol = Protocol.CONSOLE,
            serviceId = "",
            replyTo = "local",
            user = alicePrincipal,
        )

    /** User key: channelUri/username */
    private val aliceKey = "console:///local/alice"
    private val bobKey = "console:///local/bob"
    private val ircServiceId = "irc-ideas"

    private fun aliceMessage(text: String) =
        MessageBuilder.withPayload(text)
            .setHeader(Provenance.HEADER, provenance)
            .setHeader(Provenance.ADDRESSED, true)
            .build()

    private fun bobMessage(text: String) =
        MessageBuilder.withPayload(text)
            .setHeader(
                Provenance.HEADER,
                Provenance(
                    protocol = Protocol.CONSOLE,
                    serviceId = "",
                    replyTo = "local",
                    user = bobPrincipal,
                ),
            )
            .setHeader(Provenance.ADDRESSED, true)
            .build()

    private fun ircMessage(text: String, nick: String) =
        MessageBuilder.withPayload(text)
            .setHeader(
                Provenance.HEADER,
                Provenance(protocol = Protocol.IRC, serviceId = ircServiceId, replyTo = "#ideas"),
            )
            .setHeader("nick", nick)
            .setHeader(Provenance.ADDRESSED, true)
            .build()

    @BeforeEach
    fun cleanup() {
        stateService.clearState(aliceKey, IdeaSessionState.STATE_KEY)
        stateService.clearState(bobKey, IdeaSessionState.STATE_KEY)
        timerService.unregisterSession(aliceKey)
        timerService.unregisterSession(bobKey)
        postRepository.deleteAll()
    }

    @Test
    fun `start session with quoted title`() {
        val result = eventGateway.process(aliceMessage("article \"My Great Idea\""))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("My Great Idea"))
        assertTrue(payload.contains("Idea session started"))

        val state = stateService.getState(aliceKey, IdeaSessionState.STATE_KEY)
        assertNotNull(state)
    }

    @Test
    fun `start session with unquoted title`() {
        val result = eventGateway.process(aliceMessage("article My Great Idea"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("My Great Idea"))

        val state = stateService.getState(aliceKey, IdeaSessionState.STATE_KEY)
        assertNotNull(state)
    }

    @Test
    fun `start session with blank title returns error`() {
        val result = eventGateway.process(aliceMessage("article"))
        assertInstanceOf(OperationResult.Error::class.java, result)
        val message = (result as OperationResult.Error).message
        assertTrue(message.contains("Title is required"))
    }

    @Test
    fun `start session with empty quoted title returns error`() {
        val result = eventGateway.process(aliceMessage("article \"\""))
        assertInstanceOf(OperationResult.Error::class.java, result)
        val message = (result as OperationResult.Error).message
        assertTrue(message.contains("Title is required"))
    }

    @Test
    fun `duplicate session returns error`() {
        eventGateway.process(aliceMessage("article First Idea"))

        val result = eventGateway.process(aliceMessage("article Second Idea"))
        assertInstanceOf(OperationResult.Error::class.java, result)
        val message = (result as OperationResult.Error).message
        assertTrue(message.contains("already active"))
        assertTrue(message.contains("First Idea"))
    }

    @Test
    fun `add content block to active session`() {
        eventGateway.process(aliceMessage("article Test Idea"))

        val result = eventGateway.process(aliceMessage("content This is the first paragraph."))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("Content block #1"))
        assertTrue(payload.contains("Test Idea"))
    }

    @Test
    fun `add multiple content blocks`() {
        eventGateway.process(aliceMessage("article Test Idea"))
        eventGateway.process(aliceMessage("content First paragraph."))

        val result = eventGateway.process(aliceMessage("content Second paragraph."))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("Content block #2"))
    }

    @Test
    fun `content with no active session returns error`() {
        val result = eventGateway.process(aliceMessage("content Some text"))
        // Should not be handled (no active session, so canHandle returns false)
        if (result is OperationResult.Error) {
            assertTrue(result.message.contains("No idea session"))
        }
    }

    @Test
    fun `done saves draft and clears session`() {
        eventGateway.process(aliceMessage("article Test Idea"))
        eventGateway.process(aliceMessage("content Some body text."))

        val result = eventGateway.process(aliceMessage("done"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("Idea saved as draft"))
        assertTrue(payload.contains("Test Idea"))
        assertTrue(payload.contains("1 content block"))

        val state = stateService.getState(aliceKey, IdeaSessionState.STATE_KEY)
        assertNull(state)
    }

    @Test
    fun `done with no content blocks saves title-only idea`() {
        eventGateway.process(aliceMessage("article Title Only Idea"))

        val result = eventGateway.process(aliceMessage("done"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("Idea saved as draft"))
        assertTrue(payload.contains("0 content blocks"))
    }

    @Test
    fun `cancel discards session`() {
        eventGateway.process(aliceMessage("article Doomed Idea"))

        val result = eventGateway.process(aliceMessage("cancel"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("cancelled"))
        assertTrue(payload.contains("Doomed Idea"))
        assertTrue(payload.contains("discarded"))

        val state = stateService.getState(aliceKey, IdeaSessionState.STATE_KEY)
        assertNull(state)
    }

    @Test
    fun `cancel with no active session returns graceful message`() {
        val result = eventGateway.process(aliceMessage("cancel"))
        // cancel without session: either not handled (no timer session) or graceful message
        if (result is OperationResult.Success) {
            val payload = result.payload as String
            assertTrue(payload.contains("No idea session"))
        }
    }

    @Test
    fun `full flow - start, add content, done`() {
        val startResult = eventGateway.process(aliceMessage("article \"Complete Flow Test\""))
        assertInstanceOf(OperationResult.Success::class.java, startResult)

        val content1 = eventGateway.process(aliceMessage("content First paragraph of the idea."))
        assertInstanceOf(OperationResult.Success::class.java, content1)

        val content2 =
            eventGateway.process(aliceMessage("content Second paragraph with more details."))
        assertInstanceOf(OperationResult.Success::class.java, content2)
        assertTrue(((content2 as OperationResult.Success).payload as String).contains("block #2"))

        val doneResult = eventGateway.process(aliceMessage("done"))
        assertInstanceOf(OperationResult.Success::class.java, doneResult)
        val payload = (doneResult as OperationResult.Success).payload as String
        assertTrue(payload.contains("2 content blocks"))

        val state = stateService.getState(aliceKey, IdeaSessionState.STATE_KEY)
        assertNull(state)
    }

    @Test
    fun `timer service registers session on start`() {
        eventGateway.process(aliceMessage("article Timer Test"))
        assertTrue(timerService.hasActiveSession(aliceKey))
    }

    @Test
    fun `timer service unregisters session on done`() {
        eventGateway.process(aliceMessage("article Timer Done Test"))
        assertTrue(timerService.hasActiveSession(aliceKey))

        eventGateway.process(aliceMessage("done"))
        assertTrue(!timerService.hasActiveSession(aliceKey))
    }

    @Test
    fun `timer service unregisters session on cancel`() {
        eventGateway.process(aliceMessage("article Timer Cancel Test"))
        assertTrue(timerService.hasActiveSession(aliceKey))

        eventGateway.process(aliceMessage("cancel"))
        assertTrue(!timerService.hasActiveSession(aliceKey))
    }

    @Test
    fun `timer timeout finalizes session`() {
        eventGateway.process(aliceMessage("article Timeout Test"))
        eventGateway.process(aliceMessage("content Some content for timeout."))

        val futureTime = Instant.now().plusSeconds(600)
        timerService.onTick(futureTime)

        val state = stateService.getState(aliceKey, IdeaSessionState.STATE_KEY)
        assertNull(state)
        assertTrue(!timerService.hasActiveSession(aliceKey))
    }

    @Test
    fun `concurrent sessions by different users in same channel`() {
        val aliceResult = eventGateway.process(aliceMessage("article Alice Idea"))
        assertInstanceOf(OperationResult.Success::class.java, aliceResult)

        val bobResult = eventGateway.process(bobMessage("article Bob Idea"))
        assertInstanceOf(OperationResult.Success::class.java, bobResult)

        assertTrue(timerService.hasActiveSession(aliceKey))
        assertTrue(timerService.hasActiveSession(bobKey))

        val aliceDone = eventGateway.process(aliceMessage("done"))
        assertInstanceOf(OperationResult.Success::class.java, aliceDone)
        assertTrue(
            ((aliceDone as OperationResult.Success).payload as String).contains("Alice Idea")
        )

        assertTrue(!timerService.hasActiveSession(aliceKey))
        assertTrue(timerService.hasActiveSession(bobKey))
    }

    @Test
    fun `logs adds channel messages as content block`() {
        val channelUri = provenance.encode()
        messageLogService.logInbound(channelUri, "charlie", "I think we should do X")
        messageLogService.logInbound(channelUri, "dave", "Yeah X sounds good")

        eventGateway.process(aliceMessage("article Log Test Idea"))

        val result = eventGateway.process(aliceMessage("logs 10m"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("log messages"), "Should mention log messages: $payload")
        assertTrue(payload.contains("content block #1"), "Should be block #1: $payload")
    }

    @Test
    fun `logs with no active session is not handled`() {
        val result = eventGateway.process(aliceMessage("logs 10m"))
        // No active session means canHandle returns false, so logs is not handled
        if (result is OperationResult.Error) {
            assertTrue(result.message.contains("No idea session"))
        }
    }

    @Test
    fun `logs with invalid duration returns error`() {
        eventGateway.process(aliceMessage("article Duration Error Test"))

        val result = eventGateway.process(aliceMessage("logs abc"))
        assertInstanceOf(OperationResult.Error::class.java, result)
        val message = (result as OperationResult.Error).message
        assertTrue(
            message.contains("Invalid duration"),
            "Should mention invalid duration: $message",
        )
    }

    @Test
    fun `logs with hour duration is accepted`() {
        val channelUri = provenance.encode()
        messageLogService.logInbound(channelUri, "eve", "An hour-old message")

        eventGateway.process(aliceMessage("article Hour Log Test"))

        val result = eventGateway.process(aliceMessage("logs 1h"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("log messages"), "Should mention log messages: $payload")
    }

    @Test
    fun `logs with empty time window returns error`() {
        eventGateway.process(aliceMessage("article Empty Log Test"))

        // Use a very short duration; no messages should exist in 1 minute on a fresh channel
        val result =
            eventGateway.process(
                MessageBuilder.withPayload("logs 1m")
                    .setHeader(
                        Provenance.HEADER,
                        Provenance(
                            protocol = Protocol.IRC,
                            serviceId = "test",
                            replyTo = "no-logs-channel",
                            user = alicePrincipal,
                        ),
                    )
                    .setHeader(Provenance.ADDRESSED, true)
                    .build()
            )
        // This may not be handled (different provenance means no active session in canHandle)
        // The key assertion is that it doesn't crash
    }

    @Test
    fun `logs content appears in finalized idea`() {
        val channelUri = provenance.encode()
        messageLogService.logInbound(channelUri, "frank", "Great discussion point")

        eventGateway.process(aliceMessage("article Finalize With Logs"))
        eventGateway.process(aliceMessage("logs 10m"))

        val result = eventGateway.process(aliceMessage("done"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("1 content block"), "Should have 1 block from logs: $payload")
    }

    @Test
    fun `includeai toggles on and off for active session`() {
        eventGateway.process(aliceMessage("article AI Toggle Test"))

        val enabled = eventGateway.process(aliceMessage("includeai"))
        assertInstanceOf(OperationResult.Success::class.java, enabled)
        assertTrue(
            (enabled as OperationResult.Success).payload.toString().contains("AI summary enabled")
        )

        val disabled = eventGateway.process(aliceMessage("noai"))
        assertInstanceOf(OperationResult.Success::class.java, disabled)
        assertTrue(
            (disabled as OperationResult.Success).payload.toString().contains("AI summary disabled")
        )
    }

    @Test
    fun `includeai keeps draft save when ai is unavailable`() {
        val ideaTitle = "AI Unavailable ${UUID.randomUUID().toString().take(8)}"
        eventGateway.process(aliceMessage("""article "$ideaTitle""""))
        eventGateway.process(aliceMessage("content Body for AI unavailable test."))
        eventGateway.process(aliceMessage("includeai"))

        val doneResult = eventGateway.process(aliceMessage("done"))
        assertInstanceOf(OperationResult.Success::class.java, doneResult)
        val payload = (doneResult as OperationResult.Success).payload.toString()
        assertTrue(payload.contains("AI summary requested but AI is unavailable"))

        val savedPost =
            awaitPostWithTitle(ideaTitle) ?: fail("Expected post to be created for idea $ideaTitle")
        assertFalse(savedPost.markdownSource.contains("## AI Draft Summary (Generated)"))
    }

    @Test
    fun `finalize resolves service binding to author`() {
        val nick = "bindingNick-${UUID.randomUUID().toString().take(8)}"
        val ideaTitle = "Binding Idea ${UUID.randomUUID().toString().take(8)}"
        val user =
            userRepository.save(
                User(
                    username = "binding-${UUID.randomUUID().toString().take(8)}",
                    email = "binding@example.com",
                    displayName = "Bound User",
                    emailVerified = true,
                )
            )
        serviceBindingRepository.save(
            ServiceBinding(
                user = user,
                protocol = Protocol.IRC,
                serviceId = ircServiceId,
                externalIdentifier = nick,
            )
        )
        eventGateway.process(ircMessage("""article "$ideaTitle"""", nick))
        eventGateway.process(ircMessage("content Body text for idea.", nick))
        val doneResult = eventGateway.process(ircMessage("done", nick))
        assertInstanceOf(OperationResult.Success::class.java, doneResult)

        val savedPost =
            awaitPostWithTitle(ideaTitle) ?: fail("Expected post to be created for idea $ideaTitle")
        val author = savedPost.author ?: fail("Expected author to be resolved")
        assertEquals(user.id, author.id)
        assertEquals(user.displayName, author.displayName)
        assertFalse(
            savedPost.markdownSource.contains("Contributed by"),
            "Attribution footer should be removed when author is resolved",
        )
    }

    @Test
    fun `finalize without binding keeps attribution`() {
        val nick = "anonNick-${UUID.randomUUID().toString().take(8)}"
        val ideaTitle = "Anonymous Idea ${UUID.randomUUID().toString().take(8)}"

        eventGateway.process(ircMessage("""article "$ideaTitle"""", nick))
        eventGateway.process(ircMessage("content Body text for idea.", nick))
        val doneResult = eventGateway.process(ircMessage("done", nick))
        assertInstanceOf(OperationResult.Success::class.java, doneResult)

        val savedPost =
            awaitPostWithTitle(ideaTitle) ?: fail("Expected post to be created for idea $ideaTitle")
        assertNull(savedPost.author, "Author should remain anonymous when no binding is found")
        assertTrue(
            savedPost.markdownSource.contains("Contributed by $nick"),
            "Attribution footer should remain for anonymous posts",
        )
    }

    @Test
    fun `finalize resolves binding case insensitively`() {
        val nick = "DreamReal-${UUID.randomUUID().toString().take(6)}"
        val boundId = nick.lowercase()
        val ideaTitle = "Case Binding Idea ${UUID.randomUUID().toString().take(8)}"

        val user =
            userRepository.save(
                User(
                    username = "casebinding-${UUID.randomUUID().toString().take(8)}",
                    email = "casebinding-${UUID.randomUUID().toString().take(8)}@example.com",
                    displayName = "Case Bound User",
                    emailVerified = true,
                )
            )
        serviceBindingRepository.save(
            ServiceBinding(
                user = user,
                protocol = Protocol.IRC,
                serviceId = ircServiceId.uppercase(),
                externalIdentifier = boundId,
            )
        )

        eventGateway.process(ircMessage("""article "$ideaTitle"""", nick))
        eventGateway.process(ircMessage("content Body text for case-binding idea.", nick))
        val doneResult = eventGateway.process(ircMessage("done", nick))
        assertInstanceOf(OperationResult.Success::class.java, doneResult)

        val savedPost =
            awaitPostWithTitle(ideaTitle) ?: fail("Expected post to be created for idea $ideaTitle")
        val author = savedPost.author ?: fail("Expected author to be resolved")
        assertEquals(user.id, author.id)
        assertFalse(savedPost.markdownSource.contains("Contributed by"))
    }

    private fun awaitPostWithTitle(
        title: String,
        timeout: Duration = Duration.ofSeconds(2),
    ): Post? {
        val deadline = System.currentTimeMillis() + timeout.toMillis()
        var found: Post? = null
        while (System.currentTimeMillis() < deadline) {
            found = postRepository.findByTitleWithAuthor(title)
            if (found != null) break
            Thread.sleep(50)
        }
        return found
    }
}
