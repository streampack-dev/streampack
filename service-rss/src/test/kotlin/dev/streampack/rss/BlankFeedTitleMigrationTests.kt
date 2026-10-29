/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss

import dev.streampack.rss.entity.RssFeed
import dev.streampack.rss.repository.RssFeedRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional

/**
 * V81 (#283) names a feed stored with an empty or blank title by its host. Flyway has already run
 * it on the empty test database, where it does nothing; the test runs its SQL again on stored
 * feeds.
 */
@SpringBootTest
@Transactional
class BlankFeedTitleMigrationTests {
    @Autowired lateinit var feedRepository: RssFeedRepository
    @Autowired lateinit var jdbc: JdbcTemplate

    @Test
    fun `a feed with a blank title is named by its host, and a named feed is left alone`() {
        val empty = feedRepository.save(RssFeed(feedUrl = "https://danluu.com/atom.xml"))
        val blank =
            feedRepository.save(RssFeed(feedUrl = "https://www.example.com:8443/feed", title = " "))
        val named =
            feedRepository.save(RssFeed(feedUrl = "https://inside.java/feed.xml", title = "Inside"))
        feedRepository.flush()

        jdbc.execute(
            ClassPathResource("db/migration/V81__rss_feed_blank_titles.sql")
                .getContentAsString(Charsets.UTF_8),
        )

        fun titleOf(feed: RssFeed) =
            jdbc.queryForObject(
                "SELECT title FROM rss_feeds WHERE id = ?",
                String::class.java,
                feed.id,
            )
        assertThat(titleOf(empty)).isEqualTo("danluu.com")
        assertThat(titleOf(blank)).isEqualTo("example.com")
        assertThat(titleOf(named)).isEqualTo("Inside")
    }
}
