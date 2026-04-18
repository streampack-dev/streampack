/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.operation

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.rss.entity.RssEntry
import dev.streampack.rss.entity.RssFeed
import dev.streampack.rss.model.RecordRssEntryAccessRequest
import dev.streampack.rss.repository.RssEntryRepository
import dev.streampack.rss.repository.RssFeedRepository
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class RecordRssEntryAccessOperationTests {

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var feedRepository: RssFeedRepository
    @Autowired lateinit var entryRepository: RssEntryRepository

    private lateinit var entryId: UUID

    @BeforeEach
    fun setUp() {
        val feed =
            feedRepository.save(
                RssFeed(feedUrl = "https://bytecode.news/feed.xml", title = "bytecode.news")
            )
        entryId =
            entryRepository
                .save(
                    RssEntry(
                        feed = feed,
                        guid = "entry-1",
                        link = "https://bytecode.news/posts/entry-1",
                        title = "Entry 1",
                    )
                )
                .id
    }

    @Test
    fun `record access request increments persisted usage`() {
        val result =
            eventGateway.process(
                MessageBuilder.withPayload(RecordRssEntryAccessRequest(entryId)).build()
            )

        assertEquals(OperationResult.NotHandled, result)
        entryRepository.flush()
        val refreshed = entryRepository.findById(entryId).orElseThrow()
        assertEquals(1, refreshed.accessCount)
        assertNotNull(refreshed.lastAccessedAt)
    }
}
