/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.repository

import dev.streampack.blog.entity.Post
import dev.streampack.blog.entity.PostVersion
import dev.streampack.blog.model.PostStatus
import dev.streampack.core.entity.User
import dev.streampack.core.repository.UserRepository
import dev.streampack.test.ResetDatabaseBeforeEach
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.support.TransactionTemplate

/**
 * Post versions in the database (#196). Not transactional, so each query's fetches are what its
 * callers get: a lazy association read after the call would fail here as it would in production.
 */
@SpringBootTest
@ResetDatabaseBeforeEach
class PostVersionRepositoryTests {
    @Autowired lateinit var versionRepository: PostVersionRepository
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var transactions: TransactionTemplate

    private lateinit var author: User
    private lateinit var published: Post

    @BeforeEach
    fun setUp() {
        author =
            userRepository.save(
                User(username = "reader", email = "reader@test.com", displayName = "The Reader")
            )
        published = post("Published", PostStatus.APPROVED, Instant.now().minus(1, ChronoUnit.DAYS))
    }

    private fun post(
        title: String,
        status: PostStatus,
        publishedAt: Instant?,
        deleted: Boolean = false,
    ) =
        postRepository.save(
            Post(
                title = title,
                markdownSource = "# $title",
                renderedHtml = "<h1>$title</h1>",
                status = status,
                publishedAt = publishedAt,
                author = author,
                deleted = deleted,
            )
        )

    private fun version(
        post: Post,
        variant: String? = null,
        kind: String = PostVersion.KIND_AUDIO,
    ) =
        versionRepository.saveAndFlush(
            PostVersion(
                post = post,
                kind = kind,
                variant = variant,
                mediaType = "audio/mpeg",
                size = 1234,
                durationSeconds = 61,
                note = "read by the author",
                maker = author,
                sourceHash = "a".repeat(64),
                contentHash = "b".repeat(64),
                storageKey = "ab/${UUID.randomUUID()}",
            )
        )

    @Test
    fun `saves and finds a post's versions with their makers`() {
        version(published)

        val found = versionRepository.findByPost(published.id)

        assertEquals(1, found.size)
        val v = found.single()
        assertEquals("audio/mpeg", v.mediaType)
        assertEquals(1234, v.size)
        assertEquals(61, v.durationSeconds)
        assertEquals("The Reader", v.maker?.displayName)
    }

    @Test
    fun `one version per post, kind and variant`() {
        version(published)

        assertThrows<DataIntegrityViolationException> { version(published) }
        // Another variant of the same kind is a different version.
        version(published, variant = "slow")
        assertEquals(2, versionRepository.findByPost(published.id).size)
    }

    @Test
    fun `serving finds the version with its post`() {
        version(published)

        val v = versionRepository.findByPostAndKind(published.id, PostVersion.KIND_AUDIO).single()

        assertEquals(published.id, v.post.id)
        assertEquals(PostStatus.APPROVED, v.post.status)
    }

    @Test
    fun `published audio leaves out drafts, future and deleted posts`() {
        version(published)
        version(post("Draft", PostStatus.DRAFT, null))
        version(post("Future", PostStatus.APPROVED, Instant.now().plus(1, ChronoUnit.DAYS)))
        version(
            post(
                "Deleted",
                PostStatus.APPROVED,
                Instant.now().minus(2, ChronoUnit.DAYS),
                deleted = true,
            )
        )

        val found = versionRepository.findPublishedByKind(PostVersion.KIND_AUDIO, Instant.now())

        assertEquals(listOf("Published"), found.map { it.post.title })
        assertEquals("The Reader", found.single().post.author?.displayName)
    }

    @Test
    fun `versions of several posts by kind`() {
        val other = post("Other", PostStatus.APPROVED, Instant.now().minus(3, ChronoUnit.DAYS))
        version(published)
        version(other)

        val found = versionRepository.findByPostsAndKind(listOf(published.id, other.id), "audio")

        assertEquals(setOf(published.id, other.id), found.map { it.post.id }.toSet())
    }

    @Test
    fun `kinds of several posts, a row per post and kind`() {
        val other = post("Other", PostStatus.APPROVED, Instant.now().minus(3, ChronoUnit.DAYS))
        val silent = post("Silent", PostStatus.APPROVED, Instant.now().minus(4, ChronoUnit.DAYS))
        version(published)
        // Another variant of the same kind is still the one kind.
        version(published, variant = "slow")
        version(other)

        val found = versionRepository.findKindsByPosts(listOf(published.id, other.id, silent.id))

        assertEquals(
            listOf(published.id to "audio", other.id to "audio").sortedBy { it.first },
            found.map { it.postId to it.kind }.sortedBy { it.first },
        )
    }

    @Test
    fun `deleting a post deletes its versions`() {
        val keys = listOf(version(published), version(post("Other", PostStatus.DRAFT, null)))
        assertEquals(
            keys.map { it.storageKey }.toSet(),
            versionRepository.findStorageKeysByPostAuthor(author.id).toSet(),
        )

        transactions.executeWithoutResult { postRepository.hardDeleteByAuthor(author.id) }

        assertTrue(versionRepository.findAll().isEmpty())
    }
}
