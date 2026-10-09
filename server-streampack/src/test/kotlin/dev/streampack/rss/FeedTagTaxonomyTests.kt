/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss

import com.rometools.rome.io.SyndFeedInput
import dev.streampack.ServerStreampackApplication
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.rss.entity.RssEntry
import dev.streampack.rss.entity.RssFeed
import dev.streampack.rss.model.FeedTagStatus
import dev.streampack.rss.repository.RssEntryRepository
import dev.streampack.rss.repository.RssFeedRepository
import dev.streampack.rss.repository.RssFeedTagRepository
import dev.streampack.rss.service.FeedTagService
import dev.streampack.taxonomy.model.FindTaxonomySnapshotRequest
import dev.streampack.taxonomy.model.TaxonomySnapshot
import dev.streampack.taxonomy.repository.TagRepository
import dev.streampack.test.TestChannelConfiguration
import java.io.StringReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

/**
 * Feed items don't count toward tag counts or the taxonomy (#139): a feed tag promoted to a tag is
 * a tag no post or factoid carries yet, so the snapshot (and the Atlas, built on it) is as it was.
 * Here because only the whole server has the blog's taxonomy and the RSS reader together.
 */
@SpringBootTest(
    classes = [ServerStreampackApplication::class],
    properties = ["spring.main.allow-bean-definition-overriding=true"],
)
@Import(TestChannelConfiguration::class)
@Transactional
class FeedTagTaxonomyTests {
    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var feedTags: FeedTagService
    @Autowired lateinit var feeds: RssFeedRepository
    @Autowired lateinit var entries: RssEntryRepository
    @Autowired lateinit var waiting: RssFeedTagRepository
    @Autowired lateinit var tags: TagRepository

    private fun snapshot(): TaxonomySnapshot {
        val message =
            MessageBuilder.withPayload(FindTaxonomySnapshotRequest as Any)
                .setHeader(
                    Provenance.HEADER,
                    Provenance(protocol = Protocol.HTTP, serviceId = "test", replyTo = "taxonomy"),
                )
                .build()
        val result = eventGateway.process(message) as OperationResult.Success
        return result.payload as TaxonomySnapshot
    }

    private fun poll(site: String, guids: List<String>) {
        val feed = feeds.save(RssFeed(feedUrl = "$site/feed.xml", siteUrl = site, title = site))
        val items =
            guids.joinToString("") {
                "<item><title>$it</title><link>$site/$it</link><guid>$site/$it</guid>" +
                    "<category>Quarkus-Taxonomy</category><category>Java</category></item>"
            }
        val xml =
            "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>$site</title>" +
                "<link>$site</link><description>d</description>$items</channel></rss>"
        val synd = SyndFeedInput().build(StringReader(xml))
        feedTags.record(
            synd.entries.map {
                entries.save(
                    RssEntry(feed = feed, guid = it.uri, link = it.link, title = it.title)
                ) to it
            }
        )
    }

    @Test
    fun `feed tags, promoted or mapped, leave the taxonomy as it was`() {
        val before = snapshot()
        poll("https://tax-a.example", listOf("1", "2"))
        poll("https://tax-b.example", listOf("1"))

        assertEquals(
            FeedTagStatus.PROMOTED,
            waiting.findById("quarkus taxonomy").orElseThrow().status,
        )
        assertNotNull(tags.findByName("quarkus taxonomy"))
        val after = snapshot()
        assertEquals(before, after)
        assertFalse("quarkus taxonomy" in after.tags)
        assertFalse("quarkus taxonomy" in after.aggregate)
    }
}
