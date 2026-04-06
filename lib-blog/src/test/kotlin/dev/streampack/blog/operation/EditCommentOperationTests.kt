/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.entity.Comment
import dev.streampack.blog.entity.Post
import dev.streampack.blog.model.CommentDetail
import dev.streampack.blog.model.EditCommentRequest
import dev.streampack.blog.model.PostStatus
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
class EditCommentOperationTests {

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var commentRepository: CommentRepository

    private lateinit var author: User
    private lateinit var admin: User
    private lateinit var otherUser: User
    private lateinit var publishedPost: Post
    private lateinit var recentComment: Comment
    private lateinit var oldComment: Comment
    private lateinit var deletedComment: Comment

    @BeforeEach
    fun setUp() {
        val now = Instant.now()

        author =
            userRepository.save(
                User(
                    username = "editauthor",
                    email = "editauthor@test.com",
                    displayName = "Edit Author",
                    emailVerified = true,
                    role = Role.USER,
                )
            )
        admin =
            userRepository.save(
                User(
                    username = "editadmin",
                    email = "editadmin@test.com",
                    displayName = "Edit Admin",
                    emailVerified = true,
                    role = Role.ADMIN,
                )
            )
        otherUser =
            userRepository.save(
                User(
                    username = "editother",
                    email = "editother@test.com",
                    displayName = "Other User",
                    emailVerified = true,
                    role = Role.USER,
                )
            )

        publishedPost =
            postRepository.save(
                Post(
                    title = "Edit Test Post",
                    markdownSource = "Post for edit tests.",
                    renderedHtml = "<p>Post for edit tests.</p>",
                    excerpt = "Post for edit tests.",
                    status = PostStatus.APPROVED,
                    publishedAt = now.minus(1, ChronoUnit.HOURS),
                    author = author,
                    createdAt = now,
                    updatedAt = now,
                )
            )

        recentComment =
            commentRepository.save(
                Comment(
                    post = publishedPost,
                    author = author,
                    markdownSource = "Recent comment.",
                    renderedHtml = "<p>Recent comment.</p>",
                    createdAt = now,
                    updatedAt = now,
                )
            )

        oldComment =
            commentRepository.save(
                Comment(
                    post = publishedPost,
                    author = author,
                    markdownSource = "Old comment.",
                    renderedHtml = "<p>Old comment.</p>",
                    createdAt = now.minus(10, ChronoUnit.MINUTES),
                    updatedAt = now.minus(10, ChronoUnit.MINUTES),
                )
            )

        deletedComment =
            commentRepository.save(
                Comment(
                    post = publishedPost,
                    author = author,
                    markdownSource = "Deleted comment.",
                    renderedHtml = "<p>Deleted comment.</p>",
                    deleted = true,
                    createdAt = now,
                    updatedAt = now,
                )
            )
    }

    private fun editMessage(request: EditCommentRequest, user: User?) =
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
    fun `author edits within 5-minute window`() {
        val request = EditCommentRequest(recentComment.id, "Updated comment.")
        val result = eventGateway.process(editMessage(request, author))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val detail = (result as OperationResult.Success).payload as CommentDetail
        assertEquals(recentComment.id, detail.id)
        assertTrue(detail.renderedHtml.contains("Updated comment."))
    }

    @Test
    fun `author cannot edit after 5-minute window`() {
        val request = EditCommentRequest(oldComment.id, "Updated old comment.")
        val result = eventGateway.process(editMessage(request, author))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Edit window has expired", (result as OperationResult.Error).message)
    }

    @Test
    fun `admin edits any comment regardless of time`() {
        val request = EditCommentRequest(oldComment.id, "Admin edited old comment.")
        val result = eventGateway.process(editMessage(request, admin))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val detail = (result as OperationResult.Success).payload as CommentDetail
        assertTrue(detail.renderedHtml.contains("Admin edited old comment."))
    }

    @Test
    fun `non-author cannot edit`() {
        val request = EditCommentRequest(recentComment.id, "Hacked comment.")
        val result = eventGateway.process(editMessage(request, otherUser))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals(
            "Not authorized to edit this comment",
            (result as OperationResult.Error).message,
        )
    }

    @Test
    fun `unauthenticated returns error`() {
        val request = EditCommentRequest(recentComment.id, "Anonymous edit.")
        val result = eventGateway.process(editMessage(request, null))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Authentication required", (result as OperationResult.Error).message)
    }

    @Test
    fun `blank markdownSource returns error`() {
        val request = EditCommentRequest(recentComment.id, "  ")
        val result = eventGateway.process(editMessage(request, author))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Comment content is required", (result as OperationResult.Error).message)
    }

    @Test
    fun `nonexistent comment returns error`() {
        val request = EditCommentRequest(UUID.randomUUID(), "Updated.")
        val result = eventGateway.process(editMessage(request, author))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Comment not found", (result as OperationResult.Error).message)
    }

    @Test
    fun `cannot edit soft-deleted comment`() {
        val request = EditCommentRequest(deletedComment.id, "Edit deleted.")
        val result = eventGateway.process(editMessage(request, author))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Comment not found", (result as OperationResult.Error).message)
    }

    @Test
    fun `markdown re-rendered after edit`() {
        val request = EditCommentRequest(recentComment.id, "# Heading\n\nNew content.")
        val result = eventGateway.process(editMessage(request, author))

        val detail = (result as OperationResult.Success).payload as CommentDetail
        assertTrue(detail.renderedHtml.contains("<h1>Heading</h1>"))
        assertTrue(detail.renderedHtml.contains("<p>New content.</p>"))
    }
}
