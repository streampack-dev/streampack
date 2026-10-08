/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.model.MessageKind
import dev.streampack.core.repository.MessageLogRepository
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional

/** Each logged line's kind (#174): how it's stored, filtered, and backfilled by V70. */
@SpringBootTest
@Transactional
class MessageKindTests {

    @Autowired lateinit var messageLogService: MessageLogService
    @Autowired lateinit var repository: MessageLogRepository
    @Autowired lateinit var jdbc: JdbcTemplate

    private fun channelUri() = "irc://kinds-${System.nanoTime()}/%23java"

    @Test
    fun `a line is logged with its kind, a message unless told otherwise`() {
        val uri = channelUri()
        val before = Instant.now().minusSeconds(1)
        messageLogService.logInbound(uri, "ada", "hello")
        messageLogService.logInbound(uri, "ada", "* ada joined #java", kind = MessageKind.JOIN)
        messageLogService.logOutbound(uri, "nevet", "hi ada")
        val after = Instant.now().plusSeconds(1)

        assertEquals(
            mapOf(
                "hello" to MessageKind.MESSAGE,
                "* ada joined #java" to MessageKind.JOIN,
                "hi ada" to MessageKind.MESSAGE,
            ),
            messageLogService.findMessages(uri, before, after, 100).associate {
                it.content to it.kind
            },
        )
    }

    @Test
    fun `reads narrow to the kinds asked for, and every kind by default`() {
        val uri = channelUri()
        val before = Instant.now().minusSeconds(1)
        messageLogService.logInbound(uri, "ada", "* ada joined #java", kind = MessageKind.JOIN)
        messageLogService.logInbound(uri, "ada", "ada says hello")
        messageLogService.logInbound(uri, "ada", "* ada quit (ada out)", kind = MessageKind.QUIT)
        val after = Instant.now().plusSeconds(1)
        repository.flush()

        assertEquals(3, messageLogService.findMessages(uri, before, after, 100).size)
        assertEquals(
            listOf("ada says hello"),
            messageLogService
                .findMessages(uri, before, after, 100, setOf(MessageKind.MESSAGE))
                .map { it.content },
        )
        assertEquals(
            listOf("* ada quit (ada out)"),
            messageLogService.findMessages(uri, before, after, 100, setOf(MessageKind.QUIT)).map {
                it.content
            },
        )
        assertTrue(messageLogService.findMessages(uri, before, after, 100, emptySet()).isEmpty())

        assertEquals(3, messageLogService.searchMessages(uri, "ada", null, 0, 10).totalElements)
        val messagesOnly = setOf(MessageKind.MESSAGE)
        assertEquals(
            listOf("ada says hello"),
            messageLogService.searchMessages(uri, "ada", null, 0, 10, messagesOnly).content.map {
                it.content
            },
        )
        assertEquals(
            listOf("ada says hello"),
            messageLogService.searchMessages(uri, null, "ada", 0, 10, messagesOnly).content.map {
                it.content
            },
        )
        assertEquals(
            listOf("* ada joined #java"),
            messageLogService
                .searchMessages(uri, "joined", "ada", 0, 10, MessageKind.EVENTS)
                .content
                .map { it.content },
        )
        assertTrue(messageLogService.searchMessages(uri, "ada", null, 0, 10, emptySet()).isEmpty)
    }

    @Test
    fun `direct and hidden lines stay out under every kind filter`() {
        val uri = channelUri()
        val before = Instant.now().minusSeconds(1)
        messageLogService.logInbound(uri, "ada", "a fine line")
        messageLogService.logInbound(uri, "ada", "a direct line", direct = true)
        messageLogService.logInbound(
            uri,
            "ada",
            "* ada quit (direct)",
            direct = true,
            kind = MessageKind.QUIT,
        )
        messageLogService.logInbound(uri, "ada", "* ada joined #java", kind = MessageKind.JOIN)
        messageLogService.logInbound(uri, "ada", "a line to hide")
        val after = Instant.now().plusSeconds(1)
        repository.flush()
        val toHide =
            repository.findWindowForModeration(uri, before, after, 100).filter {
                it.content == "a line to hide" || it.kind == MessageKind.JOIN
            }
        assertEquals(2, toHide.size)
        repository.setHiddenForModeration(toHide.map { it.id }, true)

        val filters =
            listOf(
                MessageKind.entries.toSet(),
                setOf(MessageKind.MESSAGE),
                MessageKind.EVENTS,
                setOf(MessageKind.QUIT),
                setOf(MessageKind.JOIN),
            )
        for (kinds in filters) {
            val read = messageLogService.findMessages(uri, before, after, 100, kinds)
            val searched = messageLogService.searchMessages(uri, "a", "ada", 0, 50, kinds).content
            val bySender = messageLogService.searchMessages(uri, null, "ada", 0, 50, kinds).content
            val byText = messageLogService.searchMessages(uri, "line", null, 0, 50, kinds).content
            for (lines in listOf(read, searched, bySender, byText)) {
                assertTrue(lines.none { it.direct || it.hidden }, "$kinds")
                assertTrue(lines.all { it.content == "a fine line" }, "$kinds: $lines")
            }
        }
    }

    // -- The V70 backfill --

    /** Runs V70's backfill statements again, over whatever rows are there now. */
    private fun backfill() {
        val sql =
            ClassPathResource("db/migration/V70__message_log_kind.sql")
                .getContentAsString(Charsets.UTF_8)
        sql.lines()
            .filterNot { it.trim().startsWith("--") }
            .joinToString("\n")
            .split(";")
            .map { it.trim() }
            .filter { it.startsWith("update", ignoreCase = true) }
            .also { assertEquals(2, it.size, "V70's two backfill updates") }
            .forEach { jdbc.execute(it) }
    }

    private fun insert(
        uri: String,
        sender: String,
        content: String,
        direction: String = "INBOUND",
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO message_log (id, provenance_uri, direction, sender, content, direct) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
            id,
            uri,
            direction,
            sender,
            content,
            uri.endsWith("/*"),
        )
        return id
    }

    private fun contentAndKind(id: UUID): String =
        jdbc.queryForObject(
            "SELECT content || ' => ' || kind FROM message_log WHERE id = ?",
            String::class.java,
            id,
        )!!

    @Test
    fun `the backfill classifies event lines from their text and leaves what people said alone`() {
        val network = "backfill-${System.nanoTime()}"
        val java = "irc://$network/%23java"
        val star = "irc://$network/*"
        val expected =
            mapOf(
                // Events, as IrcAdapter wrote them
                insert(java, "ada", "* ada joined #java") to "JOIN",
                insert(java, "unknown", "* ada joined #java") to "JOIN",
                insert(java, "ada", "* ada left #java") to "PART",
                insert(java, "ada", "* ada left #java (off to lunch)") to "PART",
                insert(java, "ada", "* ada changed the topic to: Java, the language") to "TOPIC",
                insert(java, "unknown", "* someone changed the topic to: ") to "TOPIC",
                insert(star, "ada", "* ada quit") to "QUIT",
                insert(star, "ada", "* ada quit (Ping timeout: 240 seconds)") to "QUIT",
                insert(star, "ada", "* ada is now known as ada_") to "NICK",
                // What people said, /me actions included
                insert(java, "ada", "* ada waves") to "MESSAGE",
                insert(java, "ada", "* ada quit (smoking)") to "MESSAGE",
                insert(java, "ada", "* ada is now known as the queen") to "MESSAGE",
                insert(java, "ada", "* ada joined #kotlin") to "MESSAGE",
                insert(java, "ada", "* ada joined #java and liked it") to "MESSAGE",
                insert(java, "ada", "* bob joined #java") to "MESSAGE",
                insert(java, "ada", "* ada left #kotlin") to "MESSAGE",
                insert(java, "ada", "* bob changed the topic to: lies") to "MESSAGE",
                insert(java, "ada", "ada joined #java") to "MESSAGE",
                insert(java, "nevet", "* ada joined #java", direction = "OUTBOUND") to "MESSAGE",
                insert("slack://$network/C123", "ada", "* ada joined #java") to "MESSAGE",
                insert(star, "ada", "* ada did something else") to "MESSAGE",
            )

        val unclassified = expected.keys.map { contentAndKind(it) }
        assertTrue(unclassified.all { it.endsWith(" => MESSAGE") }, "before: $unclassified")

        backfill()

        assertEquals(
            expected.map { (id, kind) ->
                contentAndKind(id).substringBeforeLast(" => ") + " => " + kind
            },
            expected.keys.map { contentAndKind(it) },
        )
    }
}
