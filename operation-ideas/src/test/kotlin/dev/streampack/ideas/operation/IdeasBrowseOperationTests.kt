/* Joseph B. Ottinger (C)2026 */
package dev.streampack.ideas.operation

import dev.streampack.blog.entity.Post
import dev.streampack.blog.entity.PostTag
import dev.streampack.blog.entity.Tag
import dev.streampack.blog.model.PostStatus
import dev.streampack.blog.repository.PostRepository
import dev.streampack.blog.repository.PostTagRepository
import dev.streampack.blog.repository.TagRepository
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import java.util.UUID
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
class IdeasBrowseOperationTests {

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var tagRepository: TagRepository
    @Autowired lateinit var postTagRepository: PostTagRepository

    private val adminPrincipal =
        UserPrincipal(
            id = UUID.randomUUID(),
            username = "admin",
            displayName = "Admin User",
            role = Role.ADMIN,
        )
    private val userPrincipal =
        UserPrincipal(
            id = UUID.randomUUID(),
            username = "regular",
            displayName = "Regular User",
            role = Role.USER,
        )

    private val adminProvenance =
        Provenance(
            protocol = Protocol.CONSOLE,
            serviceId = "",
            replyTo = "local",
            user = adminPrincipal,
        )
    private val userProvenance =
        Provenance(
            protocol = Protocol.CONSOLE,
            serviceId = "",
            replyTo = "local",
            user = userPrincipal,
        )

    private fun adminMessage(text: String) =
        MessageBuilder.withPayload(text)
            .setHeader(Provenance.HEADER, adminProvenance)
            .setHeader(Provenance.ADDRESSED, true)
            .build()

    private fun userMessage(text: String) =
        MessageBuilder.withPayload(text)
            .setHeader(Provenance.HEADER, userProvenance)
            .setHeader(Provenance.ADDRESSED, true)
            .build()

    private lateinit var ideaTag: Tag

    @BeforeEach
    fun setUp() {
        ideaTag =
            tagRepository.findByName("_idea")
                ?: tagRepository.save(Tag(name = "_idea", slug = "_idea"))
    }

    /** Creates a draft post tagged as an idea */
    private fun createIdea(title: String): Post {
        val post =
            postRepository.save(
                Post(
                    title = title,
                    markdownSource = "Test content for $title",
                    renderedHtml = "<p>Test content for $title</p>",
                    status = PostStatus.DRAFT,
                )
            )
        postTagRepository.save(PostTag(post = post, tag = ideaTag))
        return post
    }

    @Test
    fun `non-admin is rejected`() {
        val result = eventGateway.process(userMessage("ideas"))
        assertInstanceOf(OperationResult.Error::class.java, result)
        val message = (result as OperationResult.Error).message
        assertTrue(message.contains("Insufficient privileges"))
    }

    @Test
    fun `list ideas when empty`() {
        val result = eventGateway.process(adminMessage("ideas"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("No article ideas found"))
    }

    @Test
    fun `list ideas returns usage hint`() {
        createIdea("First Idea")
        createIdea("Second Idea")

        val result = eventGateway.process(adminMessage("ideas"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("remove"))
    }

    @Test
    fun `search ideas by title returns usage hint`() {
        createIdea("Kotlin Coroutines Deep Dive")
        createIdea("Spring Boot Testing Guide")

        val result = eventGateway.process(adminMessage("ideas search Kotlin"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("remove"))
    }

    @Test
    fun `search ideas with no match`() {
        createIdea("Kotlin Article")

        val result = eventGateway.process(adminMessage("ideas search Rust"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("No ideas matching"))
    }

    @Test
    fun `search with blank term returns error`() {
        val result = eventGateway.process(adminMessage("ideas search"))
        assertInstanceOf(OperationResult.Error::class.java, result)
        val message = (result as OperationResult.Error).message
        assertTrue(message.contains("Search term is required"))
    }

    @Test
    fun `remove idea by number`() {
        createIdea("Idea To Remove")

        val result = eventGateway.process(adminMessage("ideas remove #1"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("Removed idea #1"))
        assertTrue(payload.contains("Idea To Remove"))
    }

    @Test
    fun `remove idea with invalid number returns error`() {
        createIdea("Only Idea")

        val result = eventGateway.process(adminMessage("ideas remove #5"))
        assertInstanceOf(OperationResult.Error::class.java, result)
        val message = (result as OperationResult.Error).message
        assertTrue(message.contains("not found"))
        assertTrue(message.contains("1 idea"))
    }

    @Test
    fun `remove with non-numeric argument returns error`() {
        val result = eventGateway.process(adminMessage("ideas remove #abc"))
        assertInstanceOf(OperationResult.Error::class.java, result)
        val message = (result as OperationResult.Error).message
        assertTrue(message.contains("Invalid idea number"))
    }

    @Test
    fun `deleted ideas are not counted`() {
        val idea = createIdea("Deleted Idea")
        postRepository.save(idea.copy(deleted = true))
        createIdea("Visible Idea")

        val result = eventGateway.process(adminMessage("ideas"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("remove"))
    }

    @Test
    fun `approved posts with idea tag are not counted`() {
        val post =
            postRepository.save(
                Post(
                    title = "Approved Not An Idea",
                    markdownSource = "content",
                    renderedHtml = "<p>content</p>",
                    status = PostStatus.APPROVED,
                )
            )
        postTagRepository.save(PostTag(post = post, tag = ideaTag))
        createIdea("Draft Idea")

        val result = eventGateway.process(adminMessage("ideas"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("remove"))
    }

    @Test
    fun `unknown ideas subcommand returns error`() {
        val result = eventGateway.process(adminMessage("ideas foobar"))
        assertInstanceOf(OperationResult.Error::class.java, result)
        val message = (result as OperationResult.Error).message
        assertTrue(message.contains("Unknown ideas command"))
    }
}
