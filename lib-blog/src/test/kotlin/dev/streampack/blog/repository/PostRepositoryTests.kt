/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.repository

import dev.streampack.blog.entity.Post
import dev.streampack.blog.model.PostStatus
import dev.streampack.core.entity.User
import dev.streampack.core.repository.UserRepository
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class PostRepositoryTests {

    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var userRepository: UserRepository

    private lateinit var testUser: User

    @BeforeEach
    fun setUp() {
        testUser =
            userRepository.save(
                User(username = "author", email = "author@test.com", displayName = "Test Author")
            )
    }

    @Test
    fun `save and retrieve post`() {
        val post =
            postRepository.save(
                Post(
                    title = "Hello World",
                    markdownSource = "# Hello",
                    renderedHtml = "<h1>Hello</h1>",
                    author = testUser,
                )
            )
        val found = postRepository.findById(post.id).orElse(null)

        assertNotNull(found)
        assertEquals("Hello World", found.title)
        assertEquals("# Hello", found.markdownSource)
        assertEquals("<h1>Hello</h1>", found.renderedHtml)
        assertEquals(PostStatus.DRAFT, found.status)
    }

    @Test
    fun `UUIDv7 generated on save`() {
        val post =
            postRepository.save(
                Post(title = "UUID Test", markdownSource = "test", renderedHtml = "<p>test</p>")
            )

        assertNotEquals(UUID(0, 0), post.id)
        assertEquals(7, post.id.version())
    }

    @Test
    fun `findPublished returns only approved non-deleted posts with past publishedAt`() {
        val now = Instant.now()
        postRepository.save(
            Post(
                title = "Published",
                markdownSource = "published",
                renderedHtml = "<p>published</p>",
                status = PostStatus.APPROVED,
                publishedAt = now.minus(1, ChronoUnit.HOURS),
                author = testUser,
            )
        )

        val results = postRepository.findPublished(now)
        assertEquals(1, results.size)
        assertEquals("Published", results[0].title)
    }

    @Test
    fun `findPublished excludes drafts, deleted, and scheduled`() {
        val now = Instant.now()
        // Draft post
        postRepository.save(
            Post(
                title = "Draft",
                markdownSource = "draft",
                renderedHtml = "<p>draft</p>",
                status = PostStatus.DRAFT,
                author = testUser,
            )
        )
        // Deleted approved post
        postRepository.save(
            Post(
                title = "Deleted",
                markdownSource = "deleted",
                renderedHtml = "<p>deleted</p>",
                status = PostStatus.APPROVED,
                publishedAt = now.minus(1, ChronoUnit.HOURS),
                deleted = true,
                author = testUser,
            )
        )
        // Scheduled future post
        postRepository.save(
            Post(
                title = "Scheduled",
                markdownSource = "scheduled",
                renderedHtml = "<p>scheduled</p>",
                status = PostStatus.APPROVED,
                publishedAt = now.plus(1, ChronoUnit.DAYS),
                author = testUser,
            )
        )

        val results = postRepository.findPublished(now)
        assertEquals(0, results.size)
    }

    @Test
    fun `findPublished prefers lower sortOrder within the same day`() {
        val now = Instant.now().truncatedTo(ChronoUnit.DAYS).plus(12, ChronoUnit.HOURS)
        postRepository.save(
            Post(
                title = "Later Same Day",
                markdownSource = "later",
                renderedHtml = "<p>later</p>",
                status = PostStatus.APPROVED,
                publishedAt = now.plus(2, ChronoUnit.HOURS),
                sortOrder = 10,
                author = testUser,
            )
        )
        postRepository.save(
            Post(
                title = "Pinned Same Day",
                markdownSource = "pinned",
                renderedHtml = "<p>pinned</p>",
                status = PostStatus.APPROVED,
                publishedAt = now.minus(2, ChronoUnit.HOURS),
                sortOrder = 0,
                author = testUser,
            )
        )

        val results = postRepository.findPublished(now.plus(3, ChronoUnit.HOURS))

        assertEquals(listOf("Pinned Same Day", "Later Same Day"), results.map { it.title })
    }

    @Test
    fun `findPublished keeps newer days ahead of older days regardless of sortOrder`() {
        val now = Instant.now().truncatedTo(ChronoUnit.DAYS).plus(12, ChronoUnit.HOURS)
        postRepository.save(
            Post(
                title = "Older Pinned",
                markdownSource = "older",
                renderedHtml = "<p>older</p>",
                status = PostStatus.APPROVED,
                publishedAt = now.minus(1, ChronoUnit.DAYS),
                sortOrder = -100,
                author = testUser,
            )
        )
        postRepository.save(
            Post(
                title = "Today Default",
                markdownSource = "today",
                renderedHtml = "<p>today</p>",
                status = PostStatus.APPROVED,
                publishedAt = now,
                sortOrder = 0,
                author = testUser,
            )
        )

        val results = postRepository.findPublished(now.plus(1, ChronoUnit.HOURS))

        assertEquals(listOf("Today Default", "Older Pinned"), results.map { it.title })
    }

    @Test
    fun `findDrafts returns drafts only`() {
        postRepository.save(
            Post(
                title = "My Draft",
                markdownSource = "draft content",
                renderedHtml = "<p>draft content</p>",
                status = PostStatus.DRAFT,
                author = testUser,
            )
        )
        postRepository.save(
            Post(
                title = "Approved Post",
                markdownSource = "approved",
                renderedHtml = "<p>approved</p>",
                status = PostStatus.APPROVED,
                publishedAt = Instant.now(),
                author = testUser,
            )
        )

        val drafts = postRepository.findDrafts()
        assertEquals(1, drafts.size)
        assertEquals("My Draft", drafts[0].title)
    }

    @Test
    fun `findScheduled returns approved with future publishedAt`() {
        val now = Instant.now()
        postRepository.save(
            Post(
                title = "Future Post",
                markdownSource = "future",
                renderedHtml = "<p>future</p>",
                status = PostStatus.APPROVED,
                publishedAt = now.plus(7, ChronoUnit.DAYS),
                author = testUser,
            )
        )
        postRepository.save(
            Post(
                title = "Past Post",
                markdownSource = "past",
                renderedHtml = "<p>past</p>",
                status = PostStatus.APPROVED,
                publishedAt = now.minus(1, ChronoUnit.HOURS),
                author = testUser,
            )
        )

        val scheduled = postRepository.findScheduled(now)
        assertEquals(1, scheduled.size)
        assertEquals("Future Post", scheduled[0].title)
    }

    @Test
    fun `findByAuthor returns non-deleted posts for author`() {
        postRepository.save(
            Post(
                title = "Author Post",
                markdownSource = "content",
                renderedHtml = "<p>content</p>",
                author = testUser,
            )
        )
        postRepository.save(
            Post(
                title = "Deleted Author Post",
                markdownSource = "deleted",
                renderedHtml = "<p>deleted</p>",
                author = testUser,
                deleted = true,
            )
        )

        val results = postRepository.findByAuthor(testUser.id)
        assertEquals(1, results.size)
        assertEquals("Author Post", results[0].title)
    }

    @Test
    fun `findActiveById returns non-deleted post`() {
        val post =
            postRepository.save(
                Post(
                    title = "Active Post",
                    markdownSource = "active",
                    renderedHtml = "<p>active</p>",
                    author = testUser,
                )
            )

        val found = postRepository.findActiveById(post.id)
        assertNotNull(found)
        assertEquals("Active Post", found!!.title)
    }

    @Test
    fun `findActiveById returns null for deleted post`() {
        val post =
            postRepository.save(
                Post(
                    title = "Deleted Post",
                    markdownSource = "deleted",
                    renderedHtml = "<p>deleted</p>",
                    author = testUser,
                    deleted = true,
                )
            )

        val found = postRepository.findActiveById(post.id)
        assertNull(found)
    }

    @Test
    fun `nullable author for anonymous submission`() {
        val post =
            postRepository.save(
                Post(
                    title = "Anonymous Post",
                    markdownSource = "anon",
                    renderedHtml = "<p>anon</p>",
                    author = null,
                )
            )
        val found = postRepository.findById(post.id).orElse(null)

        assertNotNull(found)
        assertNull(found.author)
        assertEquals("Anonymous Post", found.title)
    }

    @Test
    fun `findCandidatesByMarkdownContaining returns posts whose markdown mentions selector`() {
        val matching =
            postRepository.save(
                Post(
                    title = "Factoid Post",
                    markdownSource = "See [[thing]] for details.",
                    renderedHtml = "<p>See [[thing]] for details.</p>",
                    author = testUser,
                )
            )
        postRepository.save(
            Post(
                title = "Other Post",
                markdownSource = "Completely unrelated text.",
                renderedHtml = "<p>Completely unrelated text.</p>",
                author = testUser,
            )
        )

        val results = postRepository.findCandidatesByMarkdownContaining("thing")

        assertEquals(listOf(matching.id), results.map { it.id })
    }

    @Test
    fun `findCandidatesByMarkdownContaining excludes deleted posts`() {
        postRepository.save(
            Post(
                title = "Deleted Factoid Post",
                markdownSource = "See [[thing]] for details.",
                renderedHtml = "<p>See [[thing]] for details.</p>",
                deleted = true,
                author = testUser,
            )
        )

        val results = postRepository.findCandidatesByMarkdownContaining("thing")

        assertTrue(results.isEmpty())
    }
}
