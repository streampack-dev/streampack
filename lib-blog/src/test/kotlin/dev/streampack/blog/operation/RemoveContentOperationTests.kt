/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.entity.Comment
import dev.streampack.blog.entity.Post
import dev.streampack.blog.entity.Slug
import dev.streampack.blog.model.ContentOperationConfirmation
import dev.streampack.blog.model.PostStatus
import dev.streampack.blog.model.RemoveContentRequest
import dev.streampack.blog.repository.CommentRepository
import dev.streampack.blog.repository.PostRepository
import dev.streampack.blog.repository.SlugRepository
import dev.streampack.core.entity.User
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.repository.UserRepository
import jakarta.persistence.EntityManager
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class RemoveContentOperationTests {

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var slugRepository: SlugRepository
    @Autowired lateinit var commentRepository: CommentRepository
    @Autowired lateinit var entityManager: EntityManager

    private lateinit var author: User
    private lateinit var admin: User
    private lateinit var regularUser: User
    private lateinit var postToRemove: Post
    private lateinit var softDeletedPost: Post

    @BeforeEach
    fun setUp() {
        val now = Instant.now()

        author =
            userRepository.save(
                User(
                    username = "removeauthor",
                    email = "removeauthor@test.com",
                    displayName = "Remove Author",
                    emailVerified = true,
                    role = Role.USER,
                )
            )
        admin =
            userRepository.save(
                User(
                    username = "removeadmin",
                    email = "removeadmin@test.com",
                    displayName = "Remove Admin",
                    emailVerified = true,
                    role = Role.ADMIN,
                )
            )
        regularUser =
            userRepository.save(
                User(
                    username = "removeregular",
                    email = "removeregular@test.com",
                    displayName = "Remove Regular",
                    emailVerified = true,
                    role = Role.USER,
                )
            )

        postToRemove =
            postRepository.save(
                Post(
                    title = "Post to Remove",
                    markdownSource = "Content to remove.",
                    renderedHtml = "<p>Content to remove.</p>",
                    excerpt = "Content to remove.",
                    status = PostStatus.APPROVED,
                    publishedAt = now.minus(1, ChronoUnit.HOURS),
                    author = author,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        slugRepository.save(
            Slug(path = "2026/02/post-to-remove", post = postToRemove, canonical = true)
        )
        commentRepository.save(
            Comment(
                post = postToRemove,
                author = author,
                markdownSource = "A comment",
                renderedHtml = "<p>A comment</p>",
            )
        )

        softDeletedPost =
            postRepository.save(
                Post(
                    title = "Soft Deleted Post",
                    markdownSource = "Soft deleted content.",
                    renderedHtml = "<p>Soft deleted content.</p>",
                    excerpt = "Soft deleted content.",
                    status = PostStatus.DRAFT,
                    author = author,
                    deleted = true,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        slugRepository.save(
            Slug(path = "2026/02/soft-deleted-post", post = softDeletedPost, canonical = true)
        )
    }

    private fun removeMessage(request: RemoveContentRequest, user: User?) =
        MessageBuilder.withPayload(request)
            .setHeader(
                Provenance.HEADER,
                Provenance(
                    protocol = Protocol.HTTP,
                    serviceId = "blog-service",
                    replyTo = "posts",
                    user =
                        user?.let {
                            UserPrincipal(
                                id = it.id,
                                username = it.username,
                                displayName = it.displayName,
                                role = it.role,
                            )
                        },
                ),
            )
            .build()

    @Test
    fun `admin removes post successfully`() {
        val request = RemoveContentRequest(postToRemove.id)
        val result = eventGateway.process(removeMessage(request, admin))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val confirmation =
            (result as OperationResult.Success).payload as ContentOperationConfirmation
        assertEquals(postToRemove.id, confirmation.id)
        assertEquals("Post permanently removed", confirmation.message)
    }

    @Test
    fun `post is hard-deleted after removal`() {
        val postId = postToRemove.id
        eventGateway.process(removeMessage(RemoveContentRequest(postId), admin))

        assertFalse(postRepository.findById(postId).isPresent)
    }

    @Test
    fun `admin can remove already soft-deleted post`() {
        val request = RemoveContentRequest(softDeletedPost.id)
        val result = eventGateway.process(removeMessage(request, admin))

        assertInstanceOf(OperationResult.Success::class.java, result)
        assertFalse(postRepository.findById(softDeletedPost.id).isPresent)
    }

    @Test
    fun `non-admin cannot remove`() {
        val request = RemoveContentRequest(postToRemove.id)
        val result = eventGateway.process(removeMessage(request, regularUser))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals(
            "Insufficient privileges: requires ADMIN",
            (result as OperationResult.Error).message,
        )
    }

    @Test
    fun `unauthenticated request returns error`() {
        val request = RemoveContentRequest(postToRemove.id)
        val result = eventGateway.process(removeMessage(request, null))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals(
            "Insufficient privileges: requires ADMIN",
            (result as OperationResult.Error).message,
        )
    }

    @Test
    fun `nonexistent post returns error`() {
        val request = RemoveContentRequest(UUID.randomUUID())
        val result = eventGateway.process(removeMessage(request, admin))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Post not found", (result as OperationResult.Error).message)
    }

    @Test
    fun `associated slugs cleaned up after removal`() {
        val postId = postToRemove.id
        eventGateway.process(removeMessage(RemoveContentRequest(postId), admin))
        entityManager.clear()

        assertTrue(slugRepository.findByPost(postId).isEmpty())
    }

    @Test
    fun `associated comments cleaned up after removal`() {
        val postId = postToRemove.id
        eventGateway.process(removeMessage(RemoveContentRequest(postId), admin))
        entityManager.clear()

        assertTrue(commentRepository.findByPost(postId).isEmpty())
    }
}
