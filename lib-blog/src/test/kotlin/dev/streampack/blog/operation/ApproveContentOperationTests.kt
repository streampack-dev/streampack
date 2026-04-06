/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.entity.Post
import dev.streampack.blog.entity.Slug
import dev.streampack.blog.model.ApproveContentRequest
import dev.streampack.blog.model.ContentDetail
import dev.streampack.blog.model.PostStatus
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class ApproveContentOperationTests {

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var slugRepository: SlugRepository

    private lateinit var author: User
    private lateinit var admin: User
    private lateinit var draftPost: Post
    private lateinit var approvedPost: Post

    @BeforeEach
    fun setUp() {
        val now = Instant.now()

        author =
            userRepository.save(
                User(
                    username = "approveauthor",
                    email = "approveauthor@test.com",
                    displayName = "Approve Author",
                    emailVerified = true,
                    role = Role.USER,
                )
            )
        admin =
            userRepository.save(
                User(
                    username = "approveadmin",
                    email = "approveadmin@test.com",
                    displayName = "Approve Admin",
                    emailVerified = true,
                    role = Role.ADMIN,
                )
            )

        draftPost =
            postRepository.save(
                Post(
                    title = "Draft for Approval",
                    markdownSource = "Draft content to approve.",
                    renderedHtml = "<p>Draft content to approve.</p>",
                    excerpt = "Draft content to approve.",
                    status = PostStatus.DRAFT,
                    author = author,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        slugRepository.save(
            Slug(path = "2026/02/draft-for-approval", post = draftPost, canonical = true)
        )

        approvedPost =
            postRepository.save(
                Post(
                    title = "Already Approved",
                    markdownSource = "Already approved content.",
                    renderedHtml = "<p>Already approved content.</p>",
                    excerpt = "Already approved content.",
                    status = PostStatus.APPROVED,
                    publishedAt = now.minus(1, ChronoUnit.HOURS),
                    author = author,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        slugRepository.save(
            Slug(path = "2026/02/already-approved", post = approvedPost, canonical = true)
        )
    }

    private fun approveMessage(request: ApproveContentRequest, user: User?) =
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
    fun `admin approves draft with immediate publishedAt`() {
        val publishAt = Instant.now()
        val request = ApproveContentRequest(draftPost.id, publishAt)
        val result = eventGateway.process(approveMessage(request, admin))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val detail = (result as OperationResult.Success).payload as ContentDetail
        assertEquals(PostStatus.APPROVED, detail.status)
        assertNotNull(detail.publishedAt)
        assertEquals("2026/02/draft-for-approval", detail.slug)
    }

    @Test
    fun `approve response includes markdownSource`() {
        val request = ApproveContentRequest(draftPost.id, Instant.now())
        val result = eventGateway.process(approveMessage(request, admin))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val detail = (result as OperationResult.Success).payload as ContentDetail
        assertEquals("Draft content to approve.", detail.markdownSource)
    }

    @Test
    fun `admin approves draft with future publishedAt for scheduling`() {
        val futureDate = Instant.now().plus(7, ChronoUnit.DAYS)
        val request = ApproveContentRequest(draftPost.id, futureDate)
        val result = eventGateway.process(approveMessage(request, admin))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val detail = (result as OperationResult.Success).payload as ContentDetail
        assertEquals(PostStatus.APPROVED, detail.status)
        assertEquals(futureDate, detail.publishedAt)
    }

    @Test
    fun `non-admin cannot approve`() {
        val request = ApproveContentRequest(draftPost.id, Instant.now())
        val result = eventGateway.process(approveMessage(request, author))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals(
            "Insufficient privileges: requires ADMIN",
            (result as OperationResult.Error).message,
        )
    }

    @Test
    fun `already-approved post returns error`() {
        val request = ApproveContentRequest(approvedPost.id, Instant.now())
        val result = eventGateway.process(approveMessage(request, admin))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Post is already approved", (result as OperationResult.Error).message)
    }

    @Test
    fun `nonexistent post returns error`() {
        val request = ApproveContentRequest(UUID.randomUUID(), Instant.now())
        val result = eventGateway.process(approveMessage(request, admin))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Post not found", (result as OperationResult.Error).message)
    }

    @Test
    fun `unauthenticated request returns error`() {
        val request = ApproveContentRequest(draftPost.id, Instant.now())
        val result = eventGateway.process(approveMessage(request, null))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals(
            "Insufficient privileges: requires ADMIN",
            (result as OperationResult.Error).message,
        )
    }
}
