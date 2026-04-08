/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.entity.Category
import dev.streampack.blog.entity.Comment
import dev.streampack.blog.entity.Post
import dev.streampack.blog.entity.PostCategory
import dev.streampack.blog.entity.Slug
import dev.streampack.blog.model.CommentDetail
import dev.streampack.blog.model.CreateCommentRequest
import dev.streampack.blog.model.PostStatus
import dev.streampack.blog.repository.CategoryRepository
import dev.streampack.blog.repository.CommentRepository
import dev.streampack.blog.repository.PostCategoryRepository
import dev.streampack.blog.repository.PostRepository
import dev.streampack.blog.repository.SlugRepository
import dev.streampack.core.entity.User
import dev.streampack.core.integration.EgressSubscriber
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.model.UserStatus
import dev.streampack.core.repository.UserRepository
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class CreateCommentOperationTests {

    @TestConfiguration
    class NotificationTestConfig {
        @Bean fun capturingMailSubscriber() = CapturingMailSubscriber()
    }

    class CapturingMailSubscriber : EgressSubscriber() {
        val received = CopyOnWriteArrayList<Pair<OperationResult, Provenance>>()

        override fun matches(provenance: Provenance): Boolean =
            provenance.protocol == Protocol.MAILTO

        override fun deliver(result: OperationResult, provenance: Provenance) {
            received.add(result to provenance)
        }

        fun reset() {
            received.clear()
        }
    }

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var commentRepository: CommentRepository
    @Autowired lateinit var categoryRepository: CategoryRepository
    @Autowired lateinit var postCategoryRepository: PostCategoryRepository
    @Autowired lateinit var slugRepository: SlugRepository
    @Autowired lateinit var capturingMailSubscriber: CapturingMailSubscriber

    private lateinit var verifiedUser: User
    private lateinit var unverifiedUser: User
    private lateinit var replyUser: User
    private lateinit var publishedPost: Post

    @BeforeEach
    fun setUp() {
        capturingMailSubscriber.reset()
        val now = Instant.now()

        verifiedUser =
            userRepository.save(
                User(
                    username = "commenter",
                    email = "commenter@test.com",
                    displayName = "Test Commenter",
                    emailVerified = true,
                    role = Role.USER,
                )
            )
        unverifiedUser =
            userRepository.save(
                User(
                    username = "unverified",
                    email = "unverified@test.com",
                    displayName = "Unverified User",
                    emailVerified = false,
                    role = Role.USER,
                )
            )
        replyUser =
            userRepository.save(
                User(
                    username = "replier",
                    email = "replier@test.com",
                    displayName = "Reply User",
                    emailVerified = true,
                    role = Role.USER,
                )
            )

        publishedPost =
            postRepository.save(
                Post(
                    title = "Test Post",
                    markdownSource = "Post content.",
                    renderedHtml = "<p>Post content.</p>",
                    excerpt = "Post content.",
                    status = PostStatus.APPROVED,
                    publishedAt = now.minus(1, ChronoUnit.HOURS),
                    author = verifiedUser,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        slugRepository.save(
            Slug(path = "2026/03/test-post", post = publishedPost, canonical = true)
        )
    }

    private fun createMessage(request: CreateCommentRequest, user: User?) =
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
    fun `successful top-level comment creation`() {
        val request = CreateCommentRequest(publishedPost.id, null, "This is a comment.")
        val result = eventGateway.process(createMessage(request, verifiedUser))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val detail = (result as OperationResult.Success).payload as CommentDetail
        assertEquals(publishedPost.id, detail.postId)
        assertEquals("Test Commenter", detail.authorDisplayName)
        assertNotNull(detail.createdAt)
    }

    @Test
    fun `successful nested comment creation`() {
        val parentComment =
            commentRepository.save(
                Comment(
                    post = publishedPost,
                    author = verifiedUser,
                    markdownSource = "Parent comment.",
                    renderedHtml = "<p>Parent comment.</p>",
                )
            )

        val request = CreateCommentRequest(publishedPost.id, parentComment.id, "This is a reply.")
        val result = eventGateway.process(createMessage(request, verifiedUser))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val detail = (result as OperationResult.Success).payload as CommentDetail
        assertEquals(publishedPost.id, detail.postId)

        val savedComment = commentRepository.findById(detail.id).orElse(null)
        assertNotNull(savedComment)
        assertNotNull(savedComment.parentComment)
        assertEquals(parentComment.id, savedComment.parentComment!!.id)
    }

    @Test
    fun `markdown rendered to HTML`() {
        val request = CreateCommentRequest(publishedPost.id, null, "**bold** and *italic*")
        val result = eventGateway.process(createMessage(request, verifiedUser))

        val detail = (result as OperationResult.Success).payload as CommentDetail
        assertTrue(detail.renderedHtml.contains("<strong>bold</strong>"))
        assertTrue(detail.renderedHtml.contains("<em>italic</em>"))
    }

    @Test
    fun `unverified email returns error`() {
        val request = CreateCommentRequest(publishedPost.id, null, "A comment.")
        val result = eventGateway.process(createMessage(request, unverifiedUser))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Email verification required", (result as OperationResult.Error).message)
    }

    @Test
    fun `unauthenticated returns error`() {
        val request = CreateCommentRequest(publishedPost.id, null, "A comment.")
        val result = eventGateway.process(createMessage(request, null))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Authentication required", (result as OperationResult.Error).message)
    }

    @Test
    fun `blank markdownSource returns error`() {
        val request = CreateCommentRequest(publishedPost.id, null, "   ")
        val result = eventGateway.process(createMessage(request, verifiedUser))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Comment content is required", (result as OperationResult.Error).message)
    }

    @Test
    fun `nonexistent post returns error`() {
        val request = CreateCommentRequest(UUID.randomUUID(), null, "A comment.")
        val result = eventGateway.process(createMessage(request, verifiedUser))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Post not found", (result as OperationResult.Error).message)
    }

    @Test
    fun `nonexistent parent comment returns error`() {
        val request = CreateCommentRequest(publishedPost.id, UUID.randomUUID(), "A reply.")
        val result = eventGateway.process(createMessage(request, verifiedUser))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Parent comment not found", (result as OperationResult.Error).message)
    }

    @Test
    fun `deleted parent comment returns not found`() {
        val deletedParent =
            commentRepository.save(
                Comment(
                    post = publishedPost,
                    author = verifiedUser,
                    markdownSource = "Deleted parent.",
                    renderedHtml = "<p>Deleted parent.</p>",
                    deleted = true,
                )
            )

        val request = CreateCommentRequest(publishedPost.id, deletedParent.id, "A reply.")
        val result = eventGateway.process(createMessage(request, replyUser))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Parent comment not found", (result as OperationResult.Error).message)
    }

    @Test
    fun `parent comment on different post returns error`() {
        val otherPost =
            postRepository.save(
                Post(
                    title = "Other Post",
                    markdownSource = "Other content.",
                    renderedHtml = "<p>Other content.</p>",
                    excerpt = "Other content.",
                    status = PostStatus.APPROVED,
                    publishedAt = Instant.now().minus(1, ChronoUnit.HOURS),
                    author = verifiedUser,
                    createdAt = Instant.now(),
                    updatedAt = Instant.now(),
                )
            )
        val commentOnOtherPost =
            commentRepository.save(
                Comment(
                    post = otherPost,
                    author = verifiedUser,
                    markdownSource = "Comment on other post.",
                    renderedHtml = "<p>Comment on other post.</p>",
                )
            )

        val request =
            CreateCommentRequest(publishedPost.id, commentOnOtherPost.id, "Cross-post reply.")
        val result = eventGateway.process(createMessage(request, verifiedUser))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals(
            "Parent comment belongs to a different post",
            (result as OperationResult.Error).message,
        )
    }

    @Test
    fun `comments disabled for sidebar content`() {
        val sidebarCategory =
            categoryRepository.findByName("_sidebar")
                ?: categoryRepository.save(Category(name = "_sidebar", slug = "_sidebar"))
        postCategoryRepository.save(PostCategory(post = publishedPost, category = sidebarCategory))

        val request = CreateCommentRequest(publishedPost.id, null, "Should fail.")
        val result = eventGateway.process(createMessage(request, verifiedUser))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals(
            "Comments are disabled for sidebar content",
            (result as OperationResult.Error).message,
        )
    }

    @Test
    // @lat: [[operations#User-Facing Operations#Blog Notifications#Reply Emails]]
    fun `top-level comment notifies the post author`() {
        val request = CreateCommentRequest(publishedPost.id, null, "This is a comment.")

        val result = eventGateway.process(createMessage(request, replyUser))

        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals(1, capturingMailSubscriber.received.size)
        assertEquals("commenter@test.com", capturingMailSubscriber.received.single().second.replyTo)
        val body =
            (capturingMailSubscriber.received.single().first as OperationResult.Success)
                .payload
                .toString()
        assertTrue(body.contains("http://localhost:3001/posts/2026/03/test-post"))
    }

    @Test
    fun `nested reply notifies the parent comment author`() {
        val parentComment =
            commentRepository.save(
                Comment(
                    post = publishedPost,
                    author = verifiedUser,
                    markdownSource = "Parent comment.",
                    renderedHtml = "<p>Parent comment.</p>",
                )
            )

        val request = CreateCommentRequest(publishedPost.id, parentComment.id, "This is a reply.")
        val result = eventGateway.process(createMessage(request, replyUser))

        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals(1, capturingMailSubscriber.received.size)
        assertEquals("commenter@test.com", capturingMailSubscriber.received.single().second.replyTo)
        val body =
            (capturingMailSubscriber.received.single().first as OperationResult.Success)
                .payload
                .toString()
        assertTrue(body.contains("http://localhost:3001/posts/2026/03/test-post"))
    }

    @Test
    fun `self-comment does not notify the author`() {
        val request = CreateCommentRequest(publishedPost.id, null, "Talking to myself.")

        val result = eventGateway.process(createMessage(request, verifiedUser))

        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals(0, capturingMailSubscriber.received.size)
    }

    @Test
    fun `comment does not notify suspended post author`() {
        val suspendedAuthor =
            userRepository.save(
                User(
                    username = "suspendedauthor",
                    email = "suspended@author.test",
                    displayName = "Suspended Author",
                    emailVerified = true,
                    role = Role.USER,
                    status = UserStatus.SUSPENDED,
                )
            )
        val suspendedPost =
            postRepository.save(
                Post(
                    title = "Suspended Post",
                    markdownSource = "Post content.",
                    renderedHtml = "<p>Post content.</p>",
                    excerpt = "Post content.",
                    status = PostStatus.APPROVED,
                    publishedAt = Instant.now().minus(1, ChronoUnit.HOURS),
                    author = suspendedAuthor,
                    createdAt = Instant.now(),
                    updatedAt = Instant.now(),
                )
            )
        slugRepository.save(
            Slug(path = "2026/03/suspended-post", post = suspendedPost, canonical = true)
        )

        val request = CreateCommentRequest(suspendedPost.id, null, "A comment.")
        val result = eventGateway.process(createMessage(request, replyUser))

        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals(0, capturingMailSubscriber.received.size)
    }
}
