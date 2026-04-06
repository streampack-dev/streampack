/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.entity.Comment
import dev.streampack.blog.entity.Post
import dev.streampack.blog.model.ContentOperationConfirmation
import dev.streampack.blog.model.PostStatus
import dev.streampack.blog.model.SoftDeleteCommentRequest
import dev.streampack.blog.repository.CommentRepository
import dev.streampack.blog.repository.PostRepository
import dev.streampack.core.entity.User
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.repository.UserRepository
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
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
class SoftDeleteCommentOperationTests {

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var commentRepository: CommentRepository

    private lateinit var author: User
    private lateinit var admin: User
    private lateinit var regularUser: User
    private lateinit var publishedPost: Post
    private lateinit var activeComment: Comment
    private lateinit var deletedComment: Comment

    @BeforeEach
    fun setUp() {
        val now = Instant.now()

        author =
            userRepository.save(
                User(
                    username = "sdauthor",
                    email = "sdauthor@test.com",
                    displayName = "SD Author",
                    emailVerified = true,
                    role = Role.USER,
                )
            )
        admin =
            userRepository.save(
                User(
                    username = "sdadmin",
                    email = "sdadmin@test.com",
                    displayName = "SD Admin",
                    emailVerified = true,
                    role = Role.ADMIN,
                )
            )
        regularUser =
            userRepository.save(
                User(
                    username = "sdregular",
                    email = "sdregular@test.com",
                    displayName = "SD Regular",
                    emailVerified = true,
                    role = Role.USER,
                )
            )

        publishedPost =
            postRepository.save(
                Post(
                    title = "SD Test Post",
                    markdownSource = "Post for soft delete tests.",
                    renderedHtml = "<p>Post for soft delete tests.</p>",
                    excerpt = "Post for soft delete tests.",
                    status = PostStatus.APPROVED,
                    publishedAt = now.minus(1, ChronoUnit.HOURS),
                    author = author,
                    createdAt = now,
                    updatedAt = now,
                )
            )

        activeComment =
            commentRepository.save(
                Comment(
                    post = publishedPost,
                    author = author,
                    markdownSource = "Active comment.",
                    renderedHtml = "<p>Active comment.</p>",
                    createdAt = now,
                    updatedAt = now,
                )
            )

        deletedComment =
            commentRepository.save(
                Comment(
                    post = publishedPost,
                    author = author,
                    markdownSource = "Already deleted.",
                    renderedHtml = "<p>Already deleted.</p>",
                    deleted = true,
                    createdAt = now,
                    updatedAt = now,
                )
            )
    }

    private fun softDeleteMessage(request: SoftDeleteCommentRequest, user: User?) =
        MessageBuilder.withPayload(request)
            .setHeader(
                Provenance.HEADER,
                Provenance(
                    protocol = Protocol.HTTP,
                    serviceId = "blog-service",
                    replyTo = "comments",
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
    fun `admin soft-deletes comment`() {
        val request = SoftDeleteCommentRequest(activeComment.id)
        val result = eventGateway.process(softDeleteMessage(request, admin))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val confirmation =
            (result as OperationResult.Success).payload as ContentOperationConfirmation
        assertEquals(activeComment.id, confirmation.id)
        assertEquals("Comment deleted", confirmation.message)

        val updated = commentRepository.findById(activeComment.id).orElse(null)
        assertTrue(updated.deleted)
    }

    @Test
    fun `non-admin cannot soft-delete`() {
        val request = SoftDeleteCommentRequest(activeComment.id)
        val result = eventGateway.process(softDeleteMessage(request, regularUser))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals(
            "Insufficient privileges: requires ADMIN",
            (result as OperationResult.Error).message,
        )
    }

    @Test
    fun `unauthenticated returns error`() {
        val request = SoftDeleteCommentRequest(activeComment.id)
        val result = eventGateway.process(softDeleteMessage(request, null))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals(
            "Insufficient privileges: requires ADMIN",
            (result as OperationResult.Error).message,
        )
    }

    @Test
    fun `nonexistent comment returns error`() {
        val request = SoftDeleteCommentRequest(UUID.randomUUID())
        val result = eventGateway.process(softDeleteMessage(request, admin))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Comment not found", (result as OperationResult.Error).message)
    }

    @Test
    fun `already soft-deleted returns error`() {
        val request = SoftDeleteCommentRequest(deletedComment.id)
        val result = eventGateway.process(softDeleteMessage(request, admin))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Comment is already deleted", (result as OperationResult.Error).message)
    }
}
