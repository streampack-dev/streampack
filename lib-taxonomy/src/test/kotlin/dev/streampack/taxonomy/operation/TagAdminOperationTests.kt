/* Joseph B. Ottinger (C)2026 */
package dev.streampack.taxonomy.operation

import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.taxonomy.TagCuration
import dev.streampack.taxonomy.TagVocabulary
import dev.streampack.taxonomy.entity.Tag
import dev.streampack.taxonomy.repository.TagRepository
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.Message
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

/** The admin text commands for the vocabulary (#140), and their falling through for others. */
@SpringBootTest
@Transactional
class TagAdminOperationTests {
    @Autowired lateinit var operation: TagAdminOperation
    @Autowired lateinit var vocabulary: TagVocabulary
    @Autowired lateinit var tags: TagRepository
    @Autowired lateinit var curation: TagCuration

    @BeforeEach
    fun setUp() {
        listOf("compilers", "java", "kotlin", "build tools").forEach {
            tags.save(Tag(name = it, slug = vocabulary.uniqueSlug(it)))
        }
    }

    private fun message(text: String, role: Role = Role.ADMIN): Message<String> =
        MessageBuilder.withPayload(text)
            .setHeader(
                Provenance.HEADER,
                Provenance(
                    protocol = Protocol.CONSOLE,
                    serviceId = "test",
                    replyTo = "local",
                    user = UserPrincipal(UUID.randomUUID(), "boss", "Boss", role),
                ),
            )
            .build()

    private fun run(text: String): String {
        val message = message(text)
        assertTrue(operation.canHandle(message), text)
        return when (val result = operation.handle(text, message)) {
            is OperationResult.Success -> result.payload.toString()
            is OperationResult.Error -> "error: ${result.message}"
            else -> error("unexpected $result")
        }
    }

    @Test
    fun `only an admin's subcommands are taken, the rest is factoid tag search`() {
        assertFalse(operation.canHandle(message("tag review", Role.USER)))
        assertFalse(operation.canHandle(message("tag alias a b", Role.USER)))
        assertFalse(operation.canHandle(message("tag kotlin")))
        assertFalse(operation.canHandle(message("tag stop")))
        assertTrue(operation.canHandle(message("tag review")))
        assertTrue(operation.canHandle(message("tag stop self-hosted")))
    }

    @Test
    fun `review lists the open queue with its hints`() {
        assertEquals("The tag review queue is empty.", run("tag review"))
        vocabulary.accept("compiler", "post")
        vocabulary.accept("java kotlin", "factoid")
        val listed = run("tag review")
        assertTrue(listed.startsWith("2 to review: "), listed)
        assertTrue(listed.contains("compiler (plural compilers)"), listed)
        assertTrue(listed.contains("java kotlin (missing_comma java+kotlin)"), listed)
    }

    @Test
    fun `alias takes two words or an equals sign`() {
        vocabulary.accept("compiler", "post")
        assertEquals(
            "'compiler' is now 'compilers': 0 post(s) and 0 factoid(s) re-pointed.",
            run("tag alias compiler compilers"),
        )
        assertEquals(
            "'build tool' is now 'build tools': 0 post(s) and 0 factoid(s) re-pointed.",
            run("tag alias build tool = build tools"),
        )
        assertEquals(
            "Tag aliases: build tool = build tools; compiler = compilers",
            run("tag aliases"),
        )
        assertEquals(
            "error: Usage: tag alias <from> = <to>",
            run("tag alias build tool build tools"),
        )
        assertEquals("'compiler' no longer means 'compilers'.", run("tag unalias compiler"))
    }

    @Test
    fun `split, keep, dismiss and the stoplist`() {
        vocabulary.accept("java kotlin", "post")
        vocabulary.accept("compiler", "post")
        vocabulary.accept("kotlins", "post")
        assertTrue(run("tag split java kotlin").startsWith("'java kotlin' is now 'java', 'kotlin'"))
        assertEquals("Kept 'compiler' as a tag.", run("tag keep compiler"))
        assertEquals("Dismissed 'kotlins' from the review queue.", run("tag dismiss kotlins"))
        assertTrue(run("tag stop Self-Hosted").startsWith("'self hosted' is stoplisted"))
        assertEquals("Tag stoplist: self hosted", run("tag stops"))
        assertEquals("'self hosted' is off the stoplist.", run("tag unstop self hosted"))
        assertEquals("error: 'nope' isn't in the queue", run("tag keep nope"))
    }

    @Test
    fun `the actor is the admin's username`() {
        run("tag stop rust")
        assertEquals("boss", curation.actions(1).single().actor)
        assertInstanceOf(
            OperationResult.Success::class.java,
            operation.handle("tag stops", message("tag stops")),
        )
    }
}
