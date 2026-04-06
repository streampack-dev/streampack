/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.entity.Comment
import dev.streampack.blog.entity.Post
import dev.streampack.blog.model.CommentThreadResponse
import dev.streampack.blog.model.FindCommentsRequest
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class FindCommentsOperationTests {

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var commentRepository: CommentRepository

    private lateinit var author: User
    private lateinit var admin: User
    private lateinit var otherUser: User
    private lateinit var publishedPost: Post

    @BeforeEach
    fun setUp() {
        val now = Instant.now()

        author =
            userRepository.save(
                User(
                    username = "findauthor",
                    email = "findauthor@test.com",
                    displayName = "Find Author",
                    emailVerified = true,
                    role = Role.USER,
                )
            )
        admin =
            userRepository.save(
                User(
                    username = "findadmin",
                    email = "findadmin@test.com",
                    displayName = "Find Admin",
                    emailVerified = true,
                    role = Role.ADMIN,
                )
            )
        otherUser =
            userRepository.save(
                User(
                    username = "findother",
                    email = "findother@test.com",
                    displayName = "Find Other",
                    emailVerified = true,
                    role = Role.USER,
                )
            )

        publishedPost =
            postRepository.save(
                Post(
                    title = "Find Test Post",
                    markdownSource = "Post for find tests.",
                    renderedHtml = "<p>Post for find tests.</p>",
                    excerpt = "Post for find tests.",
                    status = PostStatus.APPROVED,
                    publishedAt = now.minus(1, ChronoUnit.HOURS),
                    author = author,
                    createdAt = now,
                    updatedAt = now,
                )
            )
    }

    private fun findMessage(request: FindCommentsRequest, user: User?) =
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
    fun `returns tree structure with children nested under parents`() {
        val now = Instant.now()
        val parent =
            commentRepository.save(
                Comment(
                    post = publishedPost,
                    author = author,
                    markdownSource = "Parent.",
                    renderedHtml = "<p>Parent.</p>",
                    createdAt = now,
                    updatedAt = now,
                )
            )
        commentRepository.save(
            Comment(
                post = publishedPost,
                author = otherUser,
                parentComment = parent,
                markdownSource = "Child.",
                renderedHtml = "<p>Child.</p>",
                createdAt = now.plusMillis(100),
                updatedAt = now.plusMillis(100),
            )
        )

        val request = FindCommentsRequest(publishedPost.id)
        val result = eventGateway.process(findMessage(request, author))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val response = (result as OperationResult.Success).payload as CommentThreadResponse
        assertEquals(1, response.comments.size)
        assertEquals(1, response.comments[0].children.size)
        assertEquals("Find Author", response.comments[0].authorDisplayName)
        assertEquals("Find Other", response.comments[0].children[0].authorDisplayName)
    }

    @Test
    fun `soft-deleted comments show deleted placeholder with no author info`() {
        val now = Instant.now()
        commentRepository.save(
            Comment(
                post = publishedPost,
                author = author,
                markdownSource = "Secret content.",
                renderedHtml = "<p>Secret content.</p>",
                deleted = true,
                createdAt = now,
                updatedAt = now,
            )
        )

        val response = findComments(publishedPost.id, author)
        assertEquals(1, response.comments.size)
        assertEquals("[deleted]", response.comments[0].renderedHtml)
        assertEquals("Anonymous", response.comments[0].authorDisplayName)
        assertNull(response.comments[0].authorId)
        assertNull(response.comments[0].markdownSource)
        assertTrue(response.comments[0].deleted)
    }

    @Test
    fun `soft-deleted comments preserve children in tree`() {
        val now = Instant.now()
        val deletedParent =
            commentRepository.save(
                Comment(
                    post = publishedPost,
                    author = author,
                    markdownSource = "Deleted parent.",
                    renderedHtml = "<p>Deleted parent.</p>",
                    deleted = true,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        commentRepository.save(
            Comment(
                post = publishedPost,
                author = otherUser,
                parentComment = deletedParent,
                markdownSource = "Child of deleted.",
                renderedHtml = "<p>Child of deleted.</p>",
                createdAt = now.plusMillis(100),
                updatedAt = now.plusMillis(100),
            )
        )

        val response = findComments(publishedPost.id, author)
        assertEquals(1, response.comments.size)
        assertTrue(response.comments[0].deleted)
        assertEquals(1, response.comments[0].children.size)
        assertFalse(response.comments[0].children[0].deleted)
    }

    @Test
    fun `empty post returns empty comments list`() {
        val response = findComments(publishedPost.id, author)
        assertEquals(0, response.comments.size)
        assertEquals(0, response.totalActiveCount)
    }

    @Test
    fun `nonexistent post returns error`() {
        val request = FindCommentsRequest(UUID.randomUUID())
        val result = eventGateway.process(findMessage(request, author))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Post not found", (result as OperationResult.Error).message)
    }

    @Test
    fun `top-level ordering by createdAt`() {
        val now = Instant.now()
        commentRepository.save(
            Comment(
                post = publishedPost,
                author = author,
                markdownSource = "Second.",
                renderedHtml = "<p>Second.</p>",
                createdAt = now.plusMillis(200),
                updatedAt = now.plusMillis(200),
            )
        )
        commentRepository.save(
            Comment(
                post = publishedPost,
                author = author,
                markdownSource = "First.",
                renderedHtml = "<p>First.</p>",
                createdAt = now.plusMillis(100),
                updatedAt = now.plusMillis(100),
            )
        )

        val response = findComments(publishedPost.id, author)
        assertEquals(2, response.comments.size)
        assertTrue(response.comments[0].createdAt <= response.comments[1].createdAt)
    }

    @Test
    fun `editable flag true for author within 5 min`() {
        val now = Instant.now()
        commentRepository.save(
            Comment(
                post = publishedPost,
                author = author,
                markdownSource = "Recent.",
                renderedHtml = "<p>Recent.</p>",
                createdAt = now,
                updatedAt = now,
            )
        )

        val response = findComments(publishedPost.id, author)
        assertTrue(response.comments[0].editable)
    }

    @Test
    fun `editable flag true for admin on any comment`() {
        val now = Instant.now()
        commentRepository.save(
            Comment(
                post = publishedPost,
                author = author,
                markdownSource = "Old.",
                renderedHtml = "<p>Old.</p>",
                createdAt = now.minus(10, ChronoUnit.MINUTES),
                updatedAt = now.minus(10, ChronoUnit.MINUTES),
            )
        )

        val response = findComments(publishedPost.id, admin)
        assertTrue(response.comments[0].editable)
        assertEquals("Old.", response.comments[0].markdownSource)
    }

    @Test
    fun `editable flag false for non-author`() {
        val now = Instant.now()
        commentRepository.save(
            Comment(
                post = publishedPost,
                author = author,
                markdownSource = "Not yours.",
                renderedHtml = "<p>Not yours.</p>",
                createdAt = now,
                updatedAt = now,
            )
        )

        val response = findComments(publishedPost.id, otherUser)
        assertFalse(response.comments[0].editable)
        assertNull(response.comments[0].markdownSource)
    }

    @Test
    fun `editable flag false for author past 5 min`() {
        val now = Instant.now()
        commentRepository.save(
            Comment(
                post = publishedPost,
                author = author,
                markdownSource = "Expired.",
                renderedHtml = "<p>Expired.</p>",
                createdAt = now.minus(10, ChronoUnit.MINUTES),
                updatedAt = now.minus(10, ChronoUnit.MINUTES),
            )
        )

        val response = findComments(publishedPost.id, author)
        assertFalse(response.comments[0].editable)
        assertNull(response.comments[0].markdownSource)
    }

    @Test
    fun `totalActiveCount excludes soft-deleted`() {
        val now = Instant.now()
        commentRepository.save(
            Comment(
                post = publishedPost,
                author = author,
                markdownSource = "Active.",
                renderedHtml = "<p>Active.</p>",
                createdAt = now,
                updatedAt = now,
            )
        )
        commentRepository.save(
            Comment(
                post = publishedPost,
                author = author,
                markdownSource = "Deleted.",
                renderedHtml = "<p>Deleted.</p>",
                deleted = true,
                createdAt = now.plusMillis(100),
                updatedAt = now.plusMillis(100),
            )
        )

        val response = findComments(publishedPost.id, author)
        assertEquals(2, response.comments.size)
        assertEquals(1, response.totalActiveCount)
    }

    @Test
    fun `anonymous user sees comments with editable false`() {
        val now = Instant.now()
        commentRepository.save(
            Comment(
                post = publishedPost,
                author = author,
                markdownSource = "Public comment.",
                renderedHtml = "<p>Public comment.</p>",
                createdAt = now,
                updatedAt = now,
            )
        )

        val response = findComments(publishedPost.id, null)
        assertEquals(1, response.comments.size)
        assertFalse(response.comments[0].editable)
        assertNull(response.comments[0].markdownSource)
    }

    private fun findComments(postId: UUID, user: User?): CommentThreadResponse {
        val request = FindCommentsRequest(postId)
        val result = eventGateway.process(findMessage(request, user))
        assertInstanceOf(OperationResult.Success::class.java, result)
        return (result as OperationResult.Success).payload as CommentThreadResponse
    }
}
