/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.entity.Comment
import dev.streampack.blog.entity.Post
import dev.streampack.blog.model.DeleteAccountRequest
import dev.streampack.blog.repository.CommentRepository
import dev.streampack.blog.repository.PostRepository
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.model.UserStatus
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.UserRegistrationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

/**
 * Integration tests for account erasure via the event system.
 *
 * Covers self-erasure, admin-erasure, sentinel creation, content reassignment, privilege
 * enforcement, and super admin protection.
 */
@SpringBootTest
@Transactional
class DeleteAccountOperationTests {

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var userRegistrationService: UserRegistrationService
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var commentRepository: CommentRepository

    private lateinit var regularUser: UserPrincipal
    private lateinit var adminUser: UserPrincipal
    private lateinit var superAdmin: UserPrincipal

    @BeforeEach
    fun setUp() {
        regularUser =
            userRegistrationService.register(
                username = "regularuser",
                email = "regular@example.com",
                displayName = "Regular User",
                protocol = Protocol.HTTP,
                serviceId = "blog-service",
                externalIdentifier = "regular@example.com",
            )
        adminUser =
            userRegistrationService.register(
                username = "adminuser",
                email = "admin@example.com",
                displayName = "Admin User",
                protocol = Protocol.HTTP,
                serviceId = "blog-service",
                externalIdentifier = "admin@example.com",
                role = Role.ADMIN,
            )
        superAdmin =
            userRegistrationService.register(
                username = "superuser",
                email = "super@example.com",
                displayName = "Super Admin",
                protocol = Protocol.HTTP,
                serviceId = "blog-service",
                externalIdentifier = "super@example.com",
                role = Role.SUPER_ADMIN,
            )
    }

    private fun deleteMessage(username: String? = null, asUser: UserPrincipal?) =
        MessageBuilder.withPayload(DeleteAccountRequest(username))
            .setHeader(
                Provenance.HEADER,
                Provenance(
                    protocol = Protocol.HTTP,
                    serviceId = "blog-service",
                    replyTo = "auth/delete",
                    user = asUser,
                ),
            )
            .build()

    @Test
    fun `self-erasure succeeds and creates sentinel`() {
        val result = eventGateway.process(deleteMessage(asUser = regularUser))

        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals("Account deleted", (result as OperationResult.Success).payload)

        // Original user is gone
        assertNull(userRepository.findByUsername("regularuser"))

        // Sentinel exists with erased status
        val sentinelUsername = "erased-${regularUser.id.toString().substring(0, 8)}"
        val sentinel = userRepository.findByUsername(sentinelUsername)
        assertNotNull(sentinel)
        assertTrue(sentinel!!.isErased())
        assertEquals("[deleted]", sentinel.displayName)
        assertEquals(Role.GUEST, sentinel.role)
    }

    @Test
    fun `erasure reassigns posts and comments to sentinel`() {
        val author = userRepository.findByUsername("regularuser")!!

        // Create a post authored by the target user
        val post =
            postRepository.saveAndFlush(
                Post(
                    title = "Test Post",
                    markdownSource = "content",
                    renderedHtml = "<p>content</p>",
                    author = author,
                )
            )

        // Create a comment by the target user
        commentRepository.saveAndFlush(
            Comment(
                post = post,
                author = author,
                markdownSource = "comment",
                renderedHtml = "<p>comment</p>",
            )
        )

        val result = eventGateway.process(deleteMessage(asUser = regularUser))
        assertInstanceOf(OperationResult.Success::class.java, result)

        // Verify content was reassigned to sentinel
        val sentinelUsername = "erased-${regularUser.id.toString().substring(0, 8)}"
        val sentinel = userRepository.findByUsername(sentinelUsername)!!

        val reassignedPost = postRepository.findById(post.id).orElse(null)
        assertNotNull(reassignedPost)
        assertEquals(sentinel.id, reassignedPost.author!!.id)

        val comments = commentRepository.findByPost(post.id)
        assertEquals(1, comments.size)
        assertEquals(sentinel.id, comments[0].author.id)
    }

    @Test
    fun `admin can erase another user`() {
        val result =
            eventGateway.process(deleteMessage(username = "regularuser", asUser = adminUser))

        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals("Account deleted", (result as OperationResult.Success).payload)
    }

    @Test
    fun `non-admin cannot erase another user`() {
        val result =
            eventGateway.process(deleteMessage(username = "adminuser", asUser = regularUser))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Insufficient privileges", (result as OperationResult.Error).message)
    }

    @Test
    fun `cannot erase a super admin`() {
        val result = eventGateway.process(deleteMessage(username = "superuser", asUser = adminUser))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Cannot delete a super admin", (result as OperationResult.Error).message)
    }

    @Test
    fun `unauthenticated request returns error`() {
        val result = eventGateway.process(deleteMessage(asUser = null))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Not authenticated", (result as OperationResult.Error).message)
    }

    @Test
    fun `erasing nonexistent user returns error`() {
        val result = eventGateway.process(deleteMessage(username = "nobody", asUser = adminUser))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("User not found", (result as OperationResult.Error).message)
    }

    @Test
    fun `cannot erase already-erased user`() {
        val user = userRepository.findByUsername("regularuser")!!
        userRepository.saveAndFlush(user.copy(status = UserStatus.ERASED))

        val result =
            eventGateway.process(deleteMessage(username = "regularuser", asUser = adminUser))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("User is already erased", (result as OperationResult.Error).message)
    }
}
