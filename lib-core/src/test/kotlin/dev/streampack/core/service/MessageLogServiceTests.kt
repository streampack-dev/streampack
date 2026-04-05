/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import com.enigmastation.streampack.core.model.MessageDirection
import com.enigmastation.streampack.core.repository.MessageLogRepository
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
    fun `findMessages excludes messages outside the time window`() {
        val uri = "test://msglog-window/unique-${System.nanoTime()}"
        messageLogService.logInbound(uri, "old", "ancient message")

        val after = Instant.now().plusSeconds(1)
        val future = after.plusSeconds(60)

        val messages = messageLogService.findMessages(uri, after, future, 100)
        assertTrue(messages.isEmpty())
    }
}
