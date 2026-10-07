/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.model.MessageDirection
import dev.streampack.core.repository.MessageLogRepository
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class MessageLogServiceTests {

    @Autowired lateinit var messageLogService: MessageLogService
    @Autowired lateinit var repository: MessageLogRepository
    @Autowired lateinit var jdbc: org.springframework.jdbc.core.JdbcTemplate

    @Test
    fun `logInbound persists inbound message`() {
        val uri = "test://msglog-inbound/unique-${System.nanoTime()}"
        messageLogService.logInbound(uri, "alice", "hello world")

        val page = repository.findByProvenanceUriOrderByTimestampDesc(uri, PageRequest.of(0, 10))
        assertEquals(1, page.totalElements)
        val entry = page.content[0]
        assertEquals(MessageDirection.INBOUND, entry.direction)
        assertEquals("alice", entry.sender)
        assertEquals("hello world", entry.content)
    }

    @Test
    fun `logOutbound persists outbound message`() {
        val uri = "test://msglog-outbound/unique-${System.nanoTime()}"
        messageLogService.logOutbound(uri, "bot", "response text")

        val page = repository.findByProvenanceUriOrderByTimestampDesc(uri, PageRequest.of(0, 10))
        assertEquals(1, page.totalElements)
        val entry = page.content[0]
        assertEquals(MessageDirection.OUTBOUND, entry.direction)
        assertEquals("bot", entry.sender)
        assertEquals("response text", entry.content)
    }

    @Test
    fun `messages are ordered by timestamp descending`() {
        val uri = "test://msglog-ordering/unique-${System.nanoTime()}"
        messageLogService.logInbound(uri, "alice", "first")
        messageLogService.logInbound(uri, "bob", "second")

        val page = repository.findByProvenanceUriOrderByTimestampDesc(uri, PageRequest.of(0, 10))
        assertEquals(2, page.totalElements)
        assertEquals("bob", page.content[0].sender)
        assertEquals("alice", page.content[1].sender)
    }

    @Test
    fun `findMessages returns messages within time bounds in chronological order`() {
        val uri = "test://msglog-bounded/unique-${System.nanoTime()}"
        val before = Instant.now().minusSeconds(1)
        messageLogService.logInbound(uri, "alice", "first")
        messageLogService.logOutbound(uri, "bot", "response")
        messageLogService.logInbound(uri, "bob", "second")
        val after = Instant.now().plusSeconds(1)

        val messages = messageLogService.findMessages(uri, before, after, 100)
        assertEquals(3, messages.size)
        assertEquals("alice", messages[0].sender)
        assertEquals("bot", messages[1].sender)
        assertEquals("bob", messages[2].sender)
    }

    @Test
    fun `findMessages respects the limit parameter`() {
        val uri = "test://msglog-limit/unique-${System.nanoTime()}"
        val before = Instant.now().minusSeconds(1)
        messageLogService.logInbound(uri, "alice", "first")
        messageLogService.logInbound(uri, "bob", "second")
        messageLogService.logInbound(uri, "carol", "third")
        val after = Instant.now().plusSeconds(1)

        val messages = messageLogService.findMessages(uri, before, after, 2)
        assertEquals(2, messages.size)
        assertEquals("alice", messages[0].sender)
        assertEquals("bob", messages[1].sender)
    }

    @Test
    fun `findLatestMessages keeps the newest past the limit, in chronological order`() {
        val uri = "test://msglog-latest/unique-${System.nanoTime()}"
        val before = Instant.now().minusSeconds(1)
        messageLogService.logInbound(uri, "alice", "first")
        messageLogService.logInbound(uri, "bob", "second")
        messageLogService.logInbound(uri, "carol", "third")
        val after = Instant.now().plusSeconds(1)

        val messages = messageLogService.findLatestMessages(uri, before, after, 2)
        assertEquals(listOf("bob", "carol"), messages.map { it.sender })
    }

    @Test
    fun `findMessages excludes messages outside the time window`() {
        val uri = "test://msglog-window/unique-${System.nanoTime()}"
        messageLogService.logInbound(uri, "old", "ancient message")

        val after = Instant.now().plusSeconds(1)
        val future = after.plusSeconds(60)

        val messages = messageLogService.findMessages(uri, after, future, 100)
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `a direct message is kept, and nothing reads it back`() {
        val uri = "irc://msglog-direct-${System.nanoTime()}/alice"
        val before = Instant.now().minusSeconds(1)
        messageLogService.logInbound(uri, "alice", "my secret plans", direct = true)
        messageLogService.logOutbound(uri, "nevet", "noted, secretly", direct = true)
        val after = Instant.now().plusSeconds(1)
        repository.flush()

        val kept =
            jdbc.queryForObject(
                "SELECT count(*) FROM message_log WHERE provenance_uri = ? AND direct",
                Int::class.java,
                uri,
            )
        assertEquals(2, kept)

        assertTrue(messageLogService.findMessages(uri, before, after, 100).isEmpty())
        assertTrue(messageLogService.findLatestMessages(uri, before, after, 100).isEmpty())
        assertEquals(null, messageLogService.findLatestMessage(uri))
        assertTrue(messageLogService.searchMessages(uri, "secret", null, 0, 10).isEmpty)
        assertTrue(messageLogService.searchMessages(uri, null, "alice", 0, 10).isEmpty)
        assertTrue(messageLogService.searchMessages(uri, "secret", "alice", 0, 10).isEmpty)
        assertTrue(
            messageLogService.findRecentMessagesBySender("alice", "irc://", 1000).none {
                it.provenanceUri == uri
            }
        )
        assertTrue(repository.findAll().none { it.provenanceUri == uri })
        assertTrue(
            repository.findByProvenanceUriOrderByTimestampDesc(uri, PageRequest.of(0, 10)).isEmpty
        )
    }

    @Test
    fun `the same reads still find what was said in a channel`() {
        val uri = "irc://msglog-direct-${System.nanoTime()}/%23java"
        val before = Instant.now().minusSeconds(1)
        messageLogService.logInbound(uri, "alice", "public plans")
        val after = Instant.now().plusSeconds(1)

        assertEquals(1, messageLogService.findMessages(uri, before, after, 100).size)
        assertEquals(
            1,
            messageLogService.searchMessages(uri, "plans", "alice", 0, 10).totalElements,
        )
        assertEquals("public plans", messageLogService.findLatestMessage(uri)?.content)
    }

    @Test
    fun `a hidden line is gone from every read but the moderation queries`() {
        val uri = "irc://msglog-hidden-${System.nanoTime()}/%23java"
        val before = Instant.now().minusSeconds(1)
        messageLogService.logInbound(uri, "alice", "a fine line")
        messageLogService.logInbound(uri, "troll", "an abusive line")
        messageLogService.logInbound(uri, "troll", "a direct line", direct = true)
        val after = Instant.now().plusSeconds(1)
        repository.flush()
        val all = repository.findWindowForModeration(uri, before, after, 100)
        assertEquals(2, all.size, "moderation reads never include a direct line")
        val abusive = all.single { it.content == "an abusive line" }
        val direct =
            jdbc.queryForObject(
                "SELECT id FROM message_log WHERE provenance_uri = ? AND direct",
                java.util.UUID::class.java,
                uri,
            )!!

        assertEquals(1, repository.setHiddenForModeration(listOf(abusive.id, direct), true))

        assertEquals(
            listOf("a fine line"),
            messageLogService.findMessages(uri, before, after, 100).map { it.content },
        )
        assertEquals(1, messageLogService.findLatestMessages(uri, before, after, 100).size)
        assertEquals("a fine line", messageLogService.findLatestMessage(uri)?.content)
        assertTrue(messageLogService.searchMessages(uri, "abusive", null, 0, 10).isEmpty)
        assertTrue(messageLogService.searchMessages(uri, null, "troll", 0, 10).isEmpty)
        assertTrue(messageLogService.searchMessages(uri, "line", "troll", 0, 10).isEmpty)
        assertTrue(
            messageLogService.findRecentMessagesBySender("troll", "irc://", 1000).none {
                it.provenanceUri == uri
            }
        )
        assertTrue(repository.findAll().none { it.provenanceUri == uri && it.sender == "troll" })

        val forModeration = repository.findWindowForModeration(uri, before, after, 100)
        assertEquals(listOf(false, true), forModeration.map { it.hidden })
        assertEquals(
            listOf("an abusive line"),
            repository.findForModeration(listOf(abusive.id, direct)).map { it.content },
        )

        assertEquals(1, repository.setHiddenForModeration(listOf(abusive.id), false))
        assertEquals(2, messageLogService.findMessages(uri, before, after, 100).size)

        assertEquals(1, repository.purgeForModeration(listOf(abusive.id, direct)))
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM message_log WHERE provenance_uri = ? AND direct",
                Int::class.java,
                uri,
            ),
            "a purge never touches a direct line",
        )
    }
}
