/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.entity.Comment
import dev.streampack.blog.entity.Post
import dev.streampack.blog.model.ExportUserDataRequest
import dev.streampack.blog.model.UserDataExport
import dev.streampack.blog.repository.CommentRepository
import dev.streampack.blog.repository.PostRepository
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.UserRegistrationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

/** Integration tests for GDPR data export via the event system */
@SpringBootTest
@Transactional
class ExportUserDataOperationTests {

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var userRegistrationService: UserRegistrationService
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var commentRepository: CommentRepository

    private lateinit var regularUser: UserPrincipal
    private lateinit var adminUser: UserPrincipal

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
    }

    private fun exportMessage(username: String? = null, asUser: UserPrincipal?) =
        MessageBuilder.withPayload(ExportUserDataRequest(username))
            .setHeader(
                Provenance.HEADER,
                Provenance(
                    protocol = Protocol.HTTP,
                    serviceId = "blog-service",
                    replyTo = "auth/export",
                    user = asUser,
                ),
            )
            .build()

    @Test
    fun `user can export own data`() {
        val author = userRepository.findByUsername("regularuser")!!

        val post =
            postRepository.saveAndFlush(
                Post(
                    title = "My Post",
                    markdownSource = "content",
                    renderedHtml = "<p>content</p>",
                    author = author,
                )
            )
        commentRepository.saveAndFlush(
            Comment(
                post = post,
                author = author,
                markdownSource = "my comment",
                renderedHtml = "<p>my comment</p>",
            )
        )

        val result = eventGateway.process(exportMessage(asUser = regularUser))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val export = (result as OperationResult.Success).payload as UserDataExport

        assertEquals("regularuser", export.profile.username)
        assertEquals("regular@example.com", export.profile.email)
        assertEquals(1, export.posts.size)
        assertEquals("My Post", export.posts[0].title)
        assertEquals(1, export.comments.size)
        assertEquals("my comment", export.comments[0].markdownSource)
    }

    @Test
    fun `admin can export another user's data`() {
        val result =
            eventGateway.process(exportMessage(username = "regularuser", asUser = adminUser))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val export = (result as OperationResult.Success).payload as UserDataExport
        assertEquals("regularuser", export.profile.username)
    }

    @Test
    fun `non-admin cannot export another user's data`() {
        val result =
            eventGateway.process(exportMessage(username = "adminuser", asUser = regularUser))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Insufficient privileges", (result as OperationResult.Error).message)
    }

    @Test
    fun `export with no content returns empty lists`() {
        val result = eventGateway.process(exportMessage(asUser = regularUser))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val export = (result as OperationResult.Success).payload as UserDataExport
        assertEquals(0, export.posts.size)
        assertEquals(0, export.comments.size)
    }

    @Test
    fun `unauthenticated request returns error`() {
        val result = eventGateway.process(exportMessage(asUser = null))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Not authenticated", (result as OperationResult.Error).message)
    }

    @Test
    fun `export nonexistent user returns error`() {
        val result = eventGateway.process(exportMessage(username = "nobody", asUser = adminUser))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("User not found", (result as OperationResult.Error).message)
    }
}
