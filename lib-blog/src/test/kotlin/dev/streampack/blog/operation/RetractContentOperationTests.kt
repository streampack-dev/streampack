/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.entity.Post
import dev.streampack.blog.entity.Slug
import dev.streampack.blog.model.ContentOperationConfirmation
import dev.streampack.blog.model.PostStatus
import dev.streampack.blog.model.RetractContentRequest
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
class RetractContentOperationTests {

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var slugRepository: SlugRepository

    private lateinit var author: User
    private lateinit var otherUser: User
    private lateinit var draftPost: Post
    private lateinit var approvedPost: Post

    @BeforeEach
    fun setUp() {
        val now = Instant.now()

        author =
            userRepository.save(
                User(
                    username = "retractauthor",
                    email = "retractauthor@test.com",
                    displayName = "Retract Author",
                    emailVerified = true,
                    role = Role.USER,
                )
            )
        otherUser =
            userRepository.save(
                User(
                    username = "retractother",
                    email = "retractother@test.com",
                    displayName = "Retract Other",
                    emailVerified = true,
                    role = Role.USER,
                )
            )

        draftPost =
            postRepository.save(
                Post(
                    title = "Draft to Retract",
                    markdownSource = "Draft content.",
                    renderedHtml = "<p>Draft content.</p>",
                    excerpt = "Draft content.",
                    status = PostStatus.DRAFT,
                    author = author,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        slugRepository.save(
            Slug(path = "2026/02/draft-to-retract", post = draftPost, canonical = true)
        )

        approvedPost =
            postRepository.save(
                Post(
                    title = "Approved Cannot Retract",
                    markdownSource = "Approved content.",
                    renderedHtml = "<p>Approved content.</p>",
                    excerpt = "Approved content.",
                    status = PostStatus.APPROVED,
                    publishedAt = now.minus(1, ChronoUnit.HOURS),
                    author = author,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        slugRepository.save(
            Slug(path = "2026/02/approved-cannot-retract", post = approvedPost, canonical = true)
        )
    }

    private fun retractMessage(request: RetractContentRequest, user: User?) =
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
    fun `author retracts own draft successfully`() {
        val request = RetractContentRequest(draftPost.id)
        val result = eventGateway.process(retractMessage(request, author))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val confirmation =
            (result as OperationResult.Success).payload as ContentOperationConfirmation
        assertEquals(draftPost.id, confirmation.id)
        assertEquals("Post retracted", confirmation.message)
    }

    @Test
    fun `post is soft-deleted after retraction`() {
        val request = RetractContentRequest(draftPost.id)
        eventGateway.process(retractMessage(request, author))

        val reloaded = postRepository.findById(draftPost.id).orElse(null)
        assertTrue(reloaded.deleted)
    }

    @Test
    fun `author cannot retract approved post`() {
        val request = RetractContentRequest(approvedPost.id)
        val result = eventGateway.process(retractMessage(request, author))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Cannot retract an approved post", (result as OperationResult.Error).message)
    }

    @Test
    fun `non-author cannot retract another's draft`() {
        val request = RetractContentRequest(draftPost.id)
        val result = eventGateway.process(retractMessage(request, otherUser))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals(
            "Only the author can retract a post",
            (result as OperationResult.Error).message,
        )
    }

    @Test
    fun `unauthenticated request returns error`() {
        val request = RetractContentRequest(draftPost.id)
        val result = eventGateway.process(retractMessage(request, null))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Authentication required", (result as OperationResult.Error).message)
    }

    @Test
    fun `nonexistent post returns error`() {
        val request = RetractContentRequest(UUID.randomUUID())
        val result = eventGateway.process(retractMessage(request, author))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Post not found", (result as OperationResult.Error).message)
    }
}
