/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.entity.Post
import dev.streampack.blog.entity.Slug
import dev.streampack.blog.model.ContentListResponse
import dev.streampack.blog.model.FindDraftsRequest
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
class FindDraftsOperationTests {

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var slugRepository: SlugRepository

    private lateinit var author: User
    private lateinit var admin: User
    private lateinit var regularUser: User

    @BeforeEach
    fun setUp() {
        val now = Instant.now()

        author =
            userRepository.save(
                User(
                    username = "draftsauthor",
                    email = "draftsauthor@test.com",
                    displayName = "Drafts Author",
                    emailVerified = true,
                    role = Role.USER,
                )
            )
        admin =
            userRepository.save(
                User(
                    username = "draftsadmin",
                    email = "draftsadmin@test.com",
                    displayName = "Drafts Admin",
                    emailVerified = true,
                    role = Role.ADMIN,
                )
            )
        regularUser =
            userRepository.save(
                User(
                    username = "draftsregular",
                    email = "draftsregular@test.com",
                    displayName = "Drafts Regular",
                    emailVerified = true,
                    role = Role.USER,
                )
            )

        // Create three drafts
        for (i in 1..3) {
            val draft =
                postRepository.save(
                    Post(
                        title = "Draft $i",
                        markdownSource = "Draft content $i.",
                        renderedHtml = "<p>Draft content $i.</p>",
                        excerpt = "Draft content $i.",
                        status = PostStatus.DRAFT,
                        author = author,
                        createdAt = now.minus(i.toLong(), ChronoUnit.HOURS),
                        updatedAt = now.minus(i.toLong(), ChronoUnit.HOURS),
                    )
                )
            slugRepository.save(Slug(path = "2026/02/draft-$i", post = draft, canonical = true))
        }

        // Create an approved post (should not appear in drafts)
        val approved =
            postRepository.save(
                Post(
                    title = "Approved Post",
                    markdownSource = "Approved.",
                    renderedHtml = "<p>Approved.</p>",
                    excerpt = "Approved.",
                    status = PostStatus.APPROVED,
                    publishedAt = now.minus(1, ChronoUnit.HOURS),
                    author = author,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        slugRepository.save(
            Slug(path = "2026/02/approved-post-drafts", post = approved, canonical = true)
        )

        // Create a soft-deleted draft (should not appear in drafts)
        val deletedDraft =
            postRepository.save(
                Post(
                    title = "Deleted Draft",
                    markdownSource = "Deleted.",
                    renderedHtml = "<p>Deleted.</p>",
                    excerpt = "Deleted.",
                    status = PostStatus.DRAFT,
                    deleted = true,
                    author = author,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        slugRepository.save(
            Slug(path = "2026/02/deleted-draft", post = deletedDraft, canonical = true)
        )
    }

    private fun draftsMessage(request: FindDraftsRequest, user: User?) =
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
    fun `admin sees all drafts`() {
        val result = eventGateway.process(draftsMessage(FindDraftsRequest(), admin))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val response = (result as OperationResult.Success).payload as ContentListResponse
        assertEquals(3, response.posts.size)
        assertEquals(3, response.totalCount)
    }

    @Test
    fun `non-admin gets error`() {
        val result = eventGateway.process(draftsMessage(FindDraftsRequest(), regularUser))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals(
            "Insufficient privileges: requires ADMIN",
            (result as OperationResult.Error).message,
        )
    }

    @Test
    fun `pagination works`() {
        val result =
            eventGateway.process(draftsMessage(FindDraftsRequest(page = 0, size = 2), admin))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val response = (result as OperationResult.Success).payload as ContentListResponse
        assertEquals(2, response.posts.size)
        assertEquals(0, response.page)
        assertEquals(2, response.totalPages)
        assertEquals(3, response.totalCount)
    }

    @Test
    fun `only DRAFT status returned`() {
        val result = eventGateway.process(draftsMessage(FindDraftsRequest(), admin))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val response = (result as OperationResult.Success).payload as ContentListResponse
        // No approved or deleted posts
        assertTrue(response.posts.all { it.title.startsWith("Draft") })
        assertTrue(response.posts.none { it.title == "Approved Post" })
        assertTrue(response.posts.none { it.title == "Deleted Draft" })
    }

    @Test
    fun `deleted flag returns only soft-deleted drafts`() {
        val result = eventGateway.process(draftsMessage(FindDraftsRequest(deleted = true), admin))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val response = (result as OperationResult.Success).payload as ContentListResponse
        assertEquals(1, response.posts.size)
        assertEquals("Deleted Draft", response.posts[0].title)
    }

    @Test
    fun `unauthenticated request returns error`() {
        val result = eventGateway.process(draftsMessage(FindDraftsRequest(), null))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals(
            "Insufficient privileges: requires ADMIN",
            (result as OperationResult.Error).message,
        )
    }
}
