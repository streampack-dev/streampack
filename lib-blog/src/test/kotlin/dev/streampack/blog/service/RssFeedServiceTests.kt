/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.service

import dev.streampack.blog.entity.Post
import dev.streampack.blog.entity.Slug
import dev.streampack.blog.model.PostStatus
import dev.streampack.blog.repository.PostRepository
import dev.streampack.blog.repository.SlugRepository
import dev.streampack.core.entity.User
import dev.streampack.core.model.Role
import dev.streampack.core.repository.UserRepository
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class RssFeedServiceTests {

    @Autowired lateinit var rssFeedService: RssFeedService
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var slugRepository: SlugRepository
    @Autowired lateinit var userRepository: UserRepository

    private lateinit var author: User

    @BeforeEach
    fun setUp() {
        author =
            userRepository.save(
                User(
                    username = "feedauthor",
                    email = "feedauthor@test.com",
                    displayName = "Feed Author",
                    emailVerified = true,
                    role = Role.USER,
                )
            )
    }

    private fun createPost(
        title: String,
        slug: String,
        status: PostStatus,
        publishedAt: Instant? = null,
        deleted: Boolean = false,
        excerpt: String? = null,
        sortOrder: Int = 0,
    ): Post {
        val post =
            postRepository.save(
                Post(
                    title = title,
                    markdownSource = "# $title",
                    renderedHtml = "<h1>$title</h1>",
                    excerpt = excerpt,
                    status = status,
                    publishedAt = publishedAt,
                    author = author,
                    deleted = deleted,
                    sortOrder = sortOrder,
                )
            )
        slugRepository.save(Slug(path = slug, post = post, canonical = true))
        return post
    }

    @Test
    fun `feed contains only published posts`() {
        val now = Instant.now()

        createPost(
            "Published One",
            "2026/02/published-one",
            PostStatus.APPROVED,
            publishedAt = now.minus(2, ChronoUnit.HOURS),
            excerpt = "First published",
        )
        createPost(
            "Published Two",
            "2026/02/published-two",
            PostStatus.APPROVED,
            publishedAt = now.minus(1, ChronoUnit.HOURS),
            excerpt = "Second published",
        )
        createPost("Draft Post", "2026/02/draft-post", PostStatus.DRAFT)
        createPost(
            "Scheduled Post",
            "2026/02/scheduled-post",
            PostStatus.APPROVED,
            publishedAt = now.plus(7, ChronoUnit.DAYS),
        )
        createPost(
            "Deleted Post",
            "2026/02/deleted-post",
            PostStatus.APPROVED,
            publishedAt = now.minus(3, ChronoUnit.HOURS),
            deleted = true,
        )

        val feed = rssFeedService.buildFeed()

        assertEquals("rss_2.0", feed.feedType)
        assertEquals(2, feed.entries.size)
    }

    @Test
    fun `feed items honor same-day sortOrder before publishedAt`() {
        val now = Instant.now()

        createPost(
            "Pinned Post",
            "2026/02/pinned-post",
            PostStatus.APPROVED,
            publishedAt = now.minus(3, ChronoUnit.HOURS),
            sortOrder = 0,
        )
        createPost(
            "Later Post",
            "2026/02/later-post",
            PostStatus.APPROVED,
            publishedAt = now.minus(1, ChronoUnit.HOURS),
            sortOrder = 5,
        )

        val feed = rssFeedService.buildFeed()

        assertEquals(2, feed.entries.size)
        assertEquals("Pinned Post", feed.entries[0].title)
        assertEquals("Later Post", feed.entries[1].title)
    }

    @Test
    fun `feed items use canonical slug for links`() {
        val now = Instant.now()

        createPost(
            "Slug Test Post",
            "2026/02/slug-test-post",
            PostStatus.APPROVED,
            publishedAt = now.minus(1, ChronoUnit.HOURS),
        )

        val feed = rssFeedService.buildFeed()

        assertEquals(1, feed.entries.size)
        assertTrue(feed.entries[0].link.endsWith("/2026/02/slug-test-post"))
    }

    @Test
    fun `feed items include author display name`() {
        val now = Instant.now()

        createPost(
            "Author Test",
            "2026/02/author-test",
            PostStatus.APPROVED,
            publishedAt = now.minus(1, ChronoUnit.HOURS),
        )

        val feed = rssFeedService.buildFeed()

        assertEquals(1, feed.entries.size)
        assertEquals("Feed Author", feed.entries[0].author)
    }

    @Test
    fun `feed items include excerpt as description`() {
        val now = Instant.now()

        createPost(
            "Excerpt Test",
            "2026/02/excerpt-test",
            PostStatus.APPROVED,
            publishedAt = now.minus(1, ChronoUnit.HOURS),
            excerpt = "This is the excerpt",
        )

        val feed = rssFeedService.buildFeed()

        assertEquals(1, feed.entries.size)
        assertEquals("This is the excerpt", feed.entries[0].description.value)
    }

    @Test
    fun `feed channel metadata comes from properties`() {
        val feed = rssFeedService.buildFeed()

        assertEquals("bytecode.news", feed.title)
        assertEquals("JVM ecosystem news and community content", feed.description)
        assertEquals("en-us", feed.language)
    }

    @Test
    fun `empty feed when no published posts exist`() {
        val feed = rssFeedService.buildFeed()

        assertEquals(0, feed.entries.size)
        assertEquals("bytecode.news", feed.title)
    }
}
