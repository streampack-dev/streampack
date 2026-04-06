/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.entity.Post
import dev.streampack.blog.model.ContentOperationConfirmation
import dev.streampack.blog.model.PostStatus
import dev.streampack.blog.model.SoftDeleteContentRequest
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
class SoftDeleteContentOperationTests {

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var postRepository: PostRepository

    private lateinit var adminUser: User
    private lateinit var regularUser: User
    private lateinit var post: Post

    @BeforeEach
    fun setUp() {
        val now = Instant.now()

        adminUser =
            userRepository.save(
                User(
                    username = "softdeladmin",
                    email = "softdeladmin@test.com",
                    displayName = "Admin",
                    emailVerified = true,
                    role = Role.ADMIN,
                )
            )

        regularUser =
            userRepository.save(
                User(
                    username = "softdeluser",
                    email = "softdeluser@test.com",
                    displayName = "User",
                    emailVerified = true,
                    role = Role.USER,
                )
            )

        post =
            postRepository.save(
                Post(
                    title = "Post to Delete",
                    markdownSource = "Content.",
                    renderedHtml = "<p>Content.</p>",
                    excerpt = "Content.",
                    status = PostStatus.DRAFT,
                    author = regularUser,
                    createdAt = now,
                    updatedAt = now,
                )
            )
    }

    private fun createMessage(request: SoftDeleteContentRequest, user: User?) =
        MessageBuilder.withPayload(request)
            .setHeader(
                Provenance.HEADER,
                Provenance(
                    protocol = Protocol.HTTP,
                    serviceId = "blog-service",
                    replyTo = "admin/posts/delete",
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
    fun `admin soft-deletes post successfully`() {
        val request = SoftDeleteContentRequest(post.id)
        val result = eventGateway.process(createMessage(request, adminUser))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val confirmation =
            (result as OperationResult.Success).payload as ContentOperationConfirmation
        assertEquals(post.id, confirmation.id)
        assertEquals("Post deleted", confirmation.message)

        val updated = postRepository.findById(post.id).orElse(null)
        assertTrue(updated.deleted)
    }

    @Test
    fun `non-admin rejected`() {
        val request = SoftDeleteContentRequest(post.id)
        val result = eventGateway.process(createMessage(request, regularUser))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals(
            "Insufficient privileges: requires ADMIN",
            (result as OperationResult.Error).message,
        )
    }

    @Test
    fun `unauthenticated rejected`() {
        val request = SoftDeleteContentRequest(post.id)
        val result = eventGateway.process(createMessage(request, null))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals(
            "Insufficient privileges: requires ADMIN",
            (result as OperationResult.Error).message,
        )
    }

    @Test
    fun `nonexistent post returns error`() {
        val request = SoftDeleteContentRequest(UUID.randomUUID())
        val result = eventGateway.process(createMessage(request, adminUser))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Post not found", (result as OperationResult.Error).message)
    }

    @Test
    fun `already-deleted post returns error`() {
        val deletedPost = postRepository.save(post.copy(deleted = true, updatedAt = Instant.now()))

        val request = SoftDeleteContentRequest(deletedPost.id)
        val result = eventGateway.process(createMessage(request, adminUser))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Post is already deleted", (result as OperationResult.Error).message)
    }
}
