/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.controller

import dev.streampack.rss.entity.RssEntry
import dev.streampack.rss.entity.RssFeed
import dev.streampack.rss.repository.RssEntryRepository
import dev.streampack.rss.repository.RssFeedRepository
import dev.streampack.test.ResetDatabaseBeforeEach
import dev.streampack.test.TestSecurityConfiguration
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ResetDatabaseBeforeEach
@Import(TestSecurityConfiguration::class)
class RssAggregatorControllerTests {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var feedRepository: RssFeedRepository
    @Autowired lateinit var entryRepository: RssEntryRepository
    private lateinit var bytecodeSpringId: UUID
    private lateinit var bytecodeUndatedId: UUID

    @BeforeEach
    fun setUp() {
        val bytecode =
            feedRepository.save(
                RssFeed(
                    feedUrl = "https://bytecode.news/feed.xml",
                    siteUrl = "https://bytecode.news",
                    title = "bytecode.news",
                    active = true,
                )
            )
        val spring =
            feedRepository.save(
                RssFeed(
                    feedUrl = "https://spring.io/blog.atom",
                    siteUrl = "https://spring.io",
                    title = "Spring Blog",
                    active = true,
                )
            )
        val inactive =
            feedRepository.save(
                RssFeed(
                    feedUrl = "https://inactive.example.com/feed.xml",
                    siteUrl = "https://inactive.example.com",
                    title = "Inactive Feed",
                    active = false,
                )
            )

        bytecodeSpringId =
            entryRepository
                .save(
                    RssEntry(
                        feed = bytecode,
                        guid = "bytecode-1",
                        link = "https://bytecode.news/posts/spring-signals",
                        title = "Spring Signals",
                        publishedAt = Instant.parse("2026-04-16T12:00:00Z"),
                    )
                )
                .id
        entryRepository.save(
            RssEntry(
                feed = bytecode,
                guid = "bytecode-2",
                link = "https://bytecode.news/posts/kotlin-roundup",
                title = "Kotlin Roundup",
                publishedAt = Instant.parse("2026-04-15T12:00:00Z"),
            )
        )
        bytecodeUndatedId =
            entryRepository
                .save(
                    RssEntry(
                        feed = bytecode,
                        guid = "bytecode-3",
                        link = "https://bytecode.news/posts/no-published-date",
                        title = "No Published Date",
                        createdAt = Instant.parse("2026-04-18T13:00:00Z"),
                    )
                )
                .id
        entryRepository.save(
            RssEntry(
                feed = spring,
                guid = "spring-1",
                link = "https://spring.io/blog/2026/04/17/spring-release",
                title = "Spring Release Notes",
                publishedAt = Instant.parse("2026-04-17T12:00:00Z"),
            )
        )
        entryRepository.save(
            RssEntry(
                feed = inactive,
                guid = "inactive-1",
                link = "https://inactive.example.com/ignored",
                title = "Should Not Appear",
                publishedAt = Instant.parse("2026-04-18T12:00:00Z"),
            )
        )
    }

    @Test
    fun `GET rss items returns newest-first stored entries`() {
        mockMvc.get("/rss/items").andExpect {
            status { isOk() }
            jsonPath("$.items.length()") { value(4) }
            jsonPath("$.items[0].id") { value(bytecodeUndatedId.toString()) }
            jsonPath("$.items[0].title") { value("No Published Date") }
            jsonPath("$.items[0].receivedAt") { value("2026-04-18T13:00:00Z") }
            jsonPath("$.items[1].title") { value("Spring Release Notes") }
            jsonPath("$.items[2].id") { value(bytecodeSpringId.toString()) }
            jsonPath("$.items[2].title") { value("Spring Signals") }
            jsonPath("$.items[3].title") { value("Kotlin Roundup") }
            jsonPath("$.totalCount") { value(4) }
        }
    }

    @Test
    fun `GET rss feeds returns active source list for aggregator UI`() {
        mockMvc.get("/rss/feeds").andExpect {
            status { isOk() }
            jsonPath("$.feeds.length()") { value(2) }
            jsonPath("$.feeds[0].title") { value("bytecode.news") }
            jsonPath("$.feeds[0].feedUrl") { value("https://bytecode.news/feed.xml") }
            jsonPath("$.feeds[0].siteUrl") { value("https://bytecode.news") }
            jsonPath("$.feeds[0].itemCount") { value(3) }
            jsonPath("$.feeds[0].latestItemTimestamp") { value("2026-04-18T13:00:00Z") }
            jsonPath("$.feeds[1].title") { value("Spring Blog") }
            jsonPath("$.feeds[1].feedUrl") { value("https://spring.io/blog.atom") }
            jsonPath("$.feeds[1].itemCount") { value(1) }
            jsonPath("$.feeds[1].latestItemTimestamp") { value("2026-04-17T12:00:00Z") }
        }
    }

    @Test
    fun `GET rss items filters by feed and title`() {
        mockMvc.get("/rss/items?feed=bytecode.news&title=spring").andExpect {
            status { isOk() }
            jsonPath("$.items.length()") { value(1) }
            jsonPath("$.items[0].feedTitle") { value("bytecode.news") }
            jsonPath("$.items[0].title") { value("Spring Signals") }
            jsonPath("$.totalCount") { value(1) }
        }
    }

    @Test
    fun `GET rss items accepts feed url filter`() {
        mockMvc.get("/rss/items?feed=https://spring.io/blog.atom").andExpect {
            status { isOk() }
            jsonPath("$.items.length()") { value(1) }
            jsonPath("$.items[0].feedTitle") { value("Spring Blog") }
        }
    }

    @Test
    fun `POST rss item access returns accepted`() {
        mockMvc.post("/rss/items/$bytecodeSpringId/access").andExpect { status { isAccepted() } }
    }
}
