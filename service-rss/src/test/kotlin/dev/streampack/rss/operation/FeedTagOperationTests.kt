/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.operation

import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.rss.entity.RssEntry
import dev.streampack.rss.entity.RssFeed
import dev.streampack.rss.repository.RssEntryRepository
import dev.streampack.rss.repository.RssFeedRepository
import dev.streampack.rss.service.FeedTagSamples.parse
import dev.streampack.rss.service.FeedTagSamples.rss
import dev.streampack.rss.service.FeedTagService
import dev.streampack.taxonomy.TagVocabulary
import dev.streampack.taxonomy.entity.Tag
import dev.streampack.taxonomy.repository.TagRepository
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.Message
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

/** The `feed tag` admin commands (#139). */
@SpringBootTest
@Transactional
class FeedTagOperationTests {
    @Autowired lateinit var operation: FeedTagOperation
    @Autowired lateinit var feeds: RssFeedRepository
    @Autowired lateinit var entries: RssEntryRepository
    @Autowired lateinit var feedTags: FeedTagService
    @Autowired lateinit var tags: TagRepository
    @Autowired lateinit var vocabulary: TagVocabulary

    @BeforeEach
    fun setUp() {
        tags.save(Tag(name = "go", slug = vocabulary.uniqueSlug("go")))
        val feed = feeds.save(RssFeed(feedUrl = "https://a.example/feed.xml", title = "A"))
        val synd =
            parse(rss("https://a.example", mapOf("1" to listOf("Golang", "Rumour", "Helidon"))))
        val entry =
            entries.save(
                RssEntry(feed = feed, guid = "https://a.example/1", link = "l", title = "One")
            )
        feedTags.record(listOf(entry to synd.entries.single()))
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
    fun `only an admin's feed tag commands are taken`() {
        assertFalse(operation.canHandle(message("feed tags", Role.USER)))
        assertFalse(operation.canHandle(message("feed list")))
        assertFalse(operation.canHandle(message("feed tag")))
        assertTrue(operation.canHandle(message("feed tag golang")))
    }

    @Test
    fun `waiting feed tags are listed, and one is shown`() {
        val listed = run("feed tags")
        assertTrue(listed.startsWith("3 feed tag(s) waiting"), listed)
        assertTrue("golang (1/1)" in listed, listed)
        val shown = run("feed tag golang")
        assertEquals("Feed tag 'golang': waiting, on 1 entries across 1 feeds. E.g. One (A)", shown)
    }

    @Test
    fun `map, ignore and create`() {
        assertEquals("Feed tag 'golang' now maps to 'go'.", run("feed tag map golang = go"))
        assertEquals(
            "Feed tag 'rumour' is ignored: it's stoplisted from now on.",
            run("feed tag ignore Rumour"),
        )
        assertEquals("Feed tag 'helidon' is now the tag 'helidon'.", run("feed tag create helidon"))
        assertEquals("No feed tags are waiting.", run("feed tags"))
        assertTrue(run("feed tag create nothing").startsWith("error: "))
    }
}
