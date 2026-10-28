/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss

import dev.streampack.rss.entity.RssEntry
import dev.streampack.rss.entity.RssFeed
import dev.streampack.rss.repository.RssEntryRepository
import dev.streampack.rss.repository.RssFeedRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional

/**
 * V82 (#290) resolves an entry's relative link against its feed's address. Flyway has already run
 * it on the empty test database, where it does nothing; the test runs its SQL again on stored
 * entries.
 */
@SpringBootTest
@Transactional
class RelativeEntryLinkMigrationTests {
    @Autowired lateinit var feedRepository: RssFeedRepository
    @Autowired lateinit var entryRepository: RssEntryRepository
    @Autowired lateinit var jdbc: JdbcTemplate

    @Test
    fun `a relative link takes its feed's address as the base, and an absolute one is left alone`() {
        val yosh =
            feedRepository.save(
                RssFeed(feedUrl = "https://www.unix.dog/~yosh/blog/feed.atom", title = "yosh"),
            )
        val root =
            feedRepository.save(RssFeed(feedUrl = "https://example.com/feed.xml", title = "r"))
        val bare = feedRepository.save(RssFeed(feedUrl = "https://example.org", title = "b"))
        fun entry(feed: RssFeed, link: String) =
            entryRepository.save(
                RssEntry(feed = feed, guid = "urn:$link", link = link, title = link),
            )
        val rooted = entry(yosh, "/~yosh/blog/pratt.html")
        val beside = entry(yosh, "getopt.html")
        val scheme = entry(yosh, "//cdn.unix.dog/a.html")
        val top = entry(root, "post.html")
        val noPath = entry(bare, "post.html")
        val absolute = entry(root, "https://elsewhere.example/post")
        entryRepository.flush()

        jdbc.execute(
            ClassPathResource("db/migration/V82__rss_entry_relative_links.sql")
                .getContentAsString(Charsets.UTF_8),
        )

        fun linkOf(entry: RssEntry) =
            jdbc.queryForObject(
                "SELECT link FROM rss_entries WHERE id = ?",
                String::class.java,
                entry.id,
            )
        assertThat(linkOf(rooted)).isEqualTo("https://www.unix.dog/~yosh/blog/pratt.html")
        assertThat(linkOf(beside)).isEqualTo("https://www.unix.dog/~yosh/blog/getopt.html")
        assertThat(linkOf(scheme)).isEqualTo("https://cdn.unix.dog/a.html")
        assertThat(linkOf(top)).isEqualTo("https://example.com/post.html")
        assertThat(linkOf(noPath)).isEqualTo("https://example.org/post.html")
        assertThat(linkOf(absolute)).isEqualTo("https://elsewhere.example/post")
        assertThat(linkOf(rooted)).isNotEqualTo(rooted.guid)
    }
}
