/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.service

import com.rometools.rome.feed.synd.SyndFeed
import dev.streampack.rss.entity.RssEntry
import dev.streampack.rss.entity.RssFeed
import dev.streampack.rss.model.DiscoveryResult
import dev.streampack.rss.model.FeedTagStatus
import dev.streampack.rss.repository.RssEntryCategoryRepository
import dev.streampack.rss.repository.RssEntryRepository
import dev.streampack.rss.repository.RssFeedRepository
import dev.streampack.rss.repository.RssFeedTagRepository
import dev.streampack.rss.service.FeedTagSamples.parse
import dev.streampack.rss.service.FeedTagSamples.rss
import dev.streampack.taxonomy.TagCuration
import dev.streampack.taxonomy.TagVocabulary
import dev.streampack.taxonomy.entity.Tag
import dev.streampack.taxonomy.model.TagResolution
import dev.streampack.taxonomy.repository.TagAliasRepository
import dev.streampack.taxonomy.repository.TagRepository
import dev.streampack.taxonomy.repository.TagReviewRepository
import dev.streampack.taxonomy.repository.TagStopRepository
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.jdbc.Sql
import org.springframework.transaction.annotation.Transactional

/**
 * Feed tags mapped onto the vocabulary (#139): mapped, ignored or waiting; promoted past the
 * threshold across distinct feeds; decided once; and an admin's map, ignore and create. Feeds are
 * parsed in memory and stored as a poll stores them: no server, no timing.
 *
 * The feed boilerplate stoplist is V74; it's run again here (it's idempotent) since other tests
 * empty the tables.
 */
@SpringBootTest
@Transactional
@Sql("classpath:db/migration/V74__rss_tag_stoplist.sql")
class FeedTagServiceTests {
    @Autowired lateinit var feedTags: FeedTagService
    @Autowired lateinit var subscriptions: RssSubscriptionService
    @Autowired lateinit var feeds: RssFeedRepository
    @Autowired lateinit var entries: RssEntryRepository
    @Autowired lateinit var categories: RssEntryCategoryRepository
    @Autowired lateinit var waiting: RssFeedTagRepository
    @Autowired lateinit var vocabulary: TagVocabulary
    @Autowired lateinit var curation: TagCuration
    @Autowired lateinit var tags: TagRepository
    @Autowired lateinit var aliases: TagAliasRepository
    @Autowired lateinit var stops: TagStopRepository
    @Autowired lateinit var reviews: TagReviewRepository

    private val t0: Instant = Instant.parse("2026-10-08T12:00:00Z")

    @BeforeEach
    fun setUp() {
        listOf("java", "kubernetes", "go").forEach {
            tags.save(Tag(name = it, slug = vocabulary.uniqueSlug(it)))
        }
        curation.alias("k8s", "kubernetes", "admin")
    }

    private fun feed(site: String): RssFeed =
        feeds.save(RssFeed(feedUrl = "$site/feed.xml", siteUrl = site, title = "Feed $site"))

    /** Stores [synd]'s entries for [feed] as a poll does, and records their tags. */
    private fun poll(feed: RssFeed, synd: SyndFeed, at: Instant = t0): List<RssEntry> {
        val window =
            synd.entries.map { s ->
                val existing = entries.findByFeedAndGuidIn(feed, listOf(s.uri)).firstOrNull()
                (existing
                    ?: entries.save(
                        RssEntry(feed = feed, guid = s.uri, link = s.link, title = s.title)
                    )) to s
            }
        feedTags.record(window, at)
        return window.map { it.first }
    }

    private fun tagsOf(entry: RssEntry) = feedTags.tagsFor(listOf(entry.id)).getValue(entry.id)

    @Test
    fun `feed tags map by canonical name and alias, and stoplisted ones are ignored`() {
        val feed = feed("https://a.example")
        val entry =
            poll(
                    feed,
                    parse(
                        rss(
                            "https://a.example",
                            mapOf("1" to listOf("Java", "K8s", "Uncategorized", "Blog", "_idea")),
                        )
                    ),
                )
                .single()
        val tagged = tagsOf(entry)
        assertEquals(listOf("Blog", "Java", "K8s", "Uncategorized", "_idea"), tagged.categories)
        assertEquals(listOf("java", "kubernetes"), tagged.tags)
        // Mapped, ignored and system tags never wait
        assertEquals(0, waiting.count())
        assertNull(tags.findByName("_idea"))
    }

    @Test
    fun `the feed boilerplate is stoplisted, and news is not`() {
        for (term in
            listOf(
                "uncategorized",
                "blog",
                "featured",
                "post",
                "posts",
                "general",
                "misc",
                "other",
                "article",
                "articles",
                "update",
                "updates",
            )) {
            assertTrue(stops.existsById(term), term)
            assertInstanceOf(TagResolution.Stopped::class.java, vocabulary.resolve(term))
        }
        assertFalse(stops.existsById("news"))
        assertInstanceOf(TagResolution.New::class.java, vocabulary.resolve("News"))
    }

    @Test
    fun `an unknown feed tag waits, counted, and creates no tag`() {
        val feed = feed("https://a.example")
        val polled =
            poll(
                feed,
                parse(
                    rss(
                        "https://a.example",
                        (1..5).associate { "$it" to listOf("Quarkus") },
                    )
                ),
            )
        val row = waiting.findById("quarkus").orElseThrow()
        assertEquals(FeedTagStatus.WAITING, row.status)
        assertEquals(5, row.entries)
        assertEquals(1, row.feeds)
        assertNull(tags.findByName("quarkus"))
        assertEquals(emptyList<String>(), tagsOf(polled.first()).tags)
    }

    @Test
    fun `a waiting tag is promoted only once on enough entries across enough feeds`() {
        val a = feed("https://a.example")
        val b = feed("https://b.example")
        poll(a, parse(rss("https://a.example", mapOf("1" to listOf("Quarkus")))))
        poll(b, parse(rss("https://b.example", mapOf("1" to listOf("quarkus")))))
        // two feeds, two entries: not yet
        assertEquals(FeedTagStatus.WAITING, waiting.findById("quarkus").orElseThrow().status)
        assertNull(tags.findByName("quarkus"))

        val later = t0.plusSeconds(3600)
        val polled =
            poll(
                b,
                parse(
                    rss(
                        "https://b.example",
                        mapOf("1" to listOf("quarkus"), "2" to listOf("Quarkus")),
                    )
                ),
                later,
            )
        val row = waiting.findById("quarkus").orElseThrow()
        assertEquals(FeedTagStatus.PROMOTED, row.status)
        assertEquals("quarkus", row.tag)
        assertEquals(3, row.entries)
        assertEquals(2, row.feeds)
        assertEquals(FeedTagService.SOURCE, row.decidedBy)
        assertEquals(later, row.decidedAt)
        assertNotNull(tags.findByName("quarkus"))
        assertEquals(listOf("quarkus"), tagsOf(polled.last()).tags)
    }

    @Test
    fun `many entries on one feed are never enough`() {
        val a = feed("https://a.example")
        poll(a, parse(rss("https://a.example", (1..10).associate { "$it" to listOf("Mine") })))
        assertEquals(FeedTagStatus.WAITING, waiting.findById("mine").orElseThrow().status)
        assertNull(tags.findByName("mine"))
    }

    @Test
    fun `a promoted tag goes through the create rule, with its review hint`() {
        tags.save(Tag(name = "compilers", slug = vocabulary.uniqueSlug("compilers")))
        val a = feed("https://a.example")
        val b = feed("https://b.example")
        poll(
            a,
            parse(
                rss(
                    "https://a.example",
                    mapOf("1" to listOf("Compiler"), "2" to listOf("compiler")),
                )
            ),
        )
        poll(b, parse(rss("https://b.example", mapOf("1" to listOf("compiler")))))
        assertEquals(FeedTagStatus.PROMOTED, waiting.findById("compiler").orElseThrow().status)
        val review = reviews.findByTag("compiler")
        assertNotNull(review)
        assertEquals(FeedTagService.SOURCE, review!!.source)
    }

    @Test
    fun `decisions are remembered across polls`() {
        val a = feed("https://a.example")
        val b = feed("https://b.example")
        val xmlA =
            rss(
                "https://a.example",
                mapOf("1" to listOf("Quarkus", "Helidon"), "2" to listOf("Quarkus")),
            )
        val xmlB = rss("https://b.example", mapOf("1" to listOf("Quarkus")))
        poll(a, parse(xmlA))
        poll(b, parse(xmlB))
        val promoted = waiting.findById("quarkus").orElseThrow()
        val categoryRows = categories.count()

        repeat(3) { i ->
            poll(a, parse(xmlA), t0.plusSeconds(60L * (i + 1)))
            poll(b, parse(xmlB), t0.plusSeconds(60L * (i + 1)))
        }
        // No duplicate rows of either kind, and nothing decided again
        assertEquals(categoryRows, categories.count())
        assertEquals(2, waiting.count())
        val after = waiting.findById("quarkus").orElseThrow()
        assertEquals(FeedTagStatus.PROMOTED, after.status)
        assertEquals(promoted.decidedAt, after.decidedAt)
        assertEquals(1, tags.findAll().count { it.name == "quarkus" })
        assertEquals(FeedTagStatus.WAITING, waiting.findById("helidon").orElseThrow().status)
        assertEquals(1, waiting.findById("helidon").orElseThrow().entries)
    }

    @Test
    fun `existing entries gain tags while in the window, and follow the feed's changes`() {
        val a = feed("https://a.example")
        val entry =
            entries.save(
                RssEntry(feed = a, guid = "https://a.example/1", link = "x", title = "Old")
            )
        assertEquals(emptyList<String>(), tagsOf(entry).categories)

        poll(a, parse(rss("https://a.example", mapOf("1" to listOf("Java", "Go")))))
        assertEquals(listOf("go", "java"), tagsOf(entry).tags)

        poll(a, parse(rss("https://a.example", mapOf("1" to listOf("Java")))))
        assertEquals(listOf("Java"), tagsOf(entry).categories)
        assertEquals(listOf("java"), tagsOf(entry).tags)
    }

    @Test
    fun `registering a feed keeps its entries' tags`() {
        val synd =
            parse(rss("https://r.example", mapOf("1" to listOf("Java"), "2" to listOf("Quarkus"))))
        subscriptions.register(DiscoveryResult("https://r.example/feed.xml", synd))
        val feed = feeds.findByFeedUrl("https://r.example/feed.xml")!!
        val stored = entries.findByFeedAndGuidIn(feed, listOf("https://r.example/1")).single()
        assertEquals(listOf("java"), tagsOf(stored).tags)
        assertEquals(1, waiting.findById("quarkus").orElseThrow().entries)
    }

    @Test
    fun `a waiting tag aliased or stoplisted elsewhere is settled by the vocabulary`() {
        val a = feed("https://a.example")
        poll(a, parse(rss("https://a.example", mapOf("1" to listOf("Golang", "Rumour")))))
        curation.alias("golang", "go", "admin")
        curation.stop("rumour", "admin")
        val listed = feedTags.list(null, 0, 10).tags.associateBy { it.name }
        assertEquals(FeedTagStatus.MAPPED, listed.getValue("golang").status)
        assertEquals("go", listed.getValue("golang").tag)
        assertEquals(FeedTagService.VOCABULARY, listed.getValue("golang").decidedBy)
        assertEquals(FeedTagStatus.IGNORED, listed.getValue("rumour").status)
    }

    @Test
    fun `an admin maps a waiting tag to an existing tag`() {
        val a = feed("https://a.example")
        val entry =
            poll(a, parse(rss("https://a.example", mapOf("1" to listOf("Golang"))))).single()
        val decided = feedTags.map("Golang", "go", "boss")
        assertEquals(FeedTagStatus.MAPPED, decided.status)
        assertEquals("go", decided.tag)
        assertEquals("boss", decided.decidedBy)
        assertEquals("go", aliases.findById("golang").orElseThrow().tag.name)
        assertEquals(listOf("go"), tagsOf(entry).tags)
        // and it stays decided
        poll(a, parse(rss("https://a.example", mapOf("1" to listOf("Golang")))), t0.plusSeconds(60))
        assertEquals(FeedTagStatus.MAPPED, waiting.findById("golang").orElseThrow().status)
        assertThrows(IllegalArgumentException::class.java) {
            feedTags.map("golang", "nope", "boss")
        }
    }

    @Test
    fun `an admin ignores a waiting tag`() {
        val a = feed("https://a.example")
        val entry =
            poll(a, parse(rss("https://a.example", mapOf("1" to listOf("Rumour", "Java")))))
                .single()
        val decided = feedTags.ignore("rumour", "boss")
        assertEquals(FeedTagStatus.IGNORED, decided.status)
        assertTrue(stops.existsById("rumour"))
        assertEquals(listOf("java"), tagsOf(entry).tags)
    }

    @Test
    fun `an admin creates a waiting tag now`() {
        val a = feed("https://a.example")
        val entry =
            poll(a, parse(rss("https://a.example", mapOf("1" to listOf("Helidon"))))).single()
        val decided = feedTags.create("Helidon", "boss")
        assertEquals(FeedTagStatus.CREATED, decided.status)
        assertEquals("helidon", decided.tag)
        assertNotNull(tags.findByName("helidon"))
        assertEquals(listOf("helidon"), tagsOf(entry).tags)
        assertEquals(1, decided.examples.size)
        assertEquals(listOf("Helidon"), decided.written)
    }

    @Test
    fun `a feed tag no feed carried can't be decided`() {
        assertThrows(FeedTagService.NotFoundException::class.java) {
            feedTags.create("never seen", "boss")
        }
    }
}
