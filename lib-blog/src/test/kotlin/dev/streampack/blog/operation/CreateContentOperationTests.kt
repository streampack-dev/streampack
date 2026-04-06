/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.entity.Category
import dev.streampack.blog.model.CreateContentRequest
import dev.streampack.blog.model.CreateContentResponse
import dev.streampack.blog.model.PostStatus
import dev.streampack.blog.repository.*
import dev.streampack.core.entity.User
import dev.streampack.core.integration.EgressSubscriber
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.*
import dev.streampack.core.repository.UserRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.Assertions.*
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
class CreateContentOperationTests {
    private val logger = org.slf4j.LoggerFactory.getLogger(CreateContentOperationTests::class.java)
    val yearMonth = DateTimeFormatter.ofPattern("yyyy/MM").format(LocalDate.now())

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

    @Autowired lateinit var slugRepository: SlugRepository

    @Autowired lateinit var tagRepository: TagRepository

    @Autowired lateinit var postTagRepository: PostTagRepository

    @Autowired lateinit var categoryRepository: CategoryRepository

    @Autowired lateinit var capturingMailSubscriber: CapturingMailSubscriber

    private lateinit var verifiedUser: User
    private lateinit var unverifiedUser: User
    private lateinit var adminUser: User
    private lateinit var superAdminUser: User

    @BeforeEach
    fun setUp() {
        capturingMailSubscriber.reset()
        verifiedUser =
            userRepository.save(
                User(
                    username = "writer",
                    email = "writer@test.com",
                    displayName = "Test Writer",
                    emailVerified = true,
                    role = Role.USER,
                )
            )
        unverifiedUser =
            userRepository.save(
                User(
                    username = "newuser",
                    email = "newuser@test.com",
                    displayName = "New User",
                    emailVerified = false,
                    role = Role.USER,
                )
            )
        adminUser =
            userRepository.save(
                User(
                    username = "editor",
                    email = "editor@test.com",
                    displayName = "Editor",
                    emailVerified = true,
                    role = Role.ADMIN,
                )
            )
        superAdminUser =
            userRepository.save(
                User(
                    username = "owner",
                    email = "owner@test.com",
                    displayName = "Owner",
                    emailVerified = true,
                    role = Role.SUPER_ADMIN,
                )
            )
    }

    private fun createMessage(request: CreateContentRequest, user: User?) =
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
    fun `successful creation returns DRAFT with generated slug`() {
        val request = CreateContentRequest("Hello World", "This is my first post.")
        val result = eventGateway.process(createMessage(request, verifiedUser))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val response = (result as OperationResult.Success).payload as CreateContentResponse
        assertEquals("Hello World", response.title)
        assertEquals(PostStatus.DRAFT, response.status)
        assertEquals(verifiedUser.id, response.authorId)
        assertEquals("Test Writer", response.authorDisplayName)
        assertNotNull(response.createdAt)
    }

    @Test
    fun `slug format matches year-month-title pattern`() {
        val request = CreateContentRequest("Hello World", "Content here.")
        val result = eventGateway.process(createMessage(request, verifiedUser))

        val response = (result as OperationResult.Success).payload as CreateContentResponse
        assertTrue(response.slug.matches(Regex("\\d{4}/\\d{2}/hello-world")))
    }

    @Test
    fun `markdown rendered to HTML in saved post`() {
        val request = CreateContentRequest("Test Post", "# Heading\n\nParagraph text.")
        val result = eventGateway.process(createMessage(request, verifiedUser))

        val response = (result as OperationResult.Success).payload as CreateContentResponse
        val savedPost = postRepository.findById(response.id).orElse(null)
        assertNotNull(savedPost)
        assertTrue(savedPost.renderedHtml.contains("<h1>Heading</h1>"))
        assertTrue(savedPost.renderedHtml.contains("<p>Paragraph text.</p>"))
    }

    @Test
    fun `excerpt auto-generated`() {
        val request = CreateContentRequest("Test Post", "This is the content of the post.")
        val result = eventGateway.process(createMessage(request, verifiedUser))

        val response = (result as OperationResult.Success).payload as CreateContentResponse
        assertNotNull(response.excerpt)
        assertTrue(response.excerpt!!.contains("content of the post"))
    }

    @Test
    fun `provided summary is persisted as excerpt`() {
        val request =
            CreateContentRequest(
                title = "Test Post",
                markdownSource = "This is the content of the post.",
                summary = "Manual summary from submitter.",
            )
        val result = eventGateway.process(createMessage(request, verifiedUser))

        val response = (result as OperationResult.Success).payload as CreateContentResponse
        assertEquals("Manual summary from submitter.", response.excerpt)
    }

    @Test
    fun `anonymous request creates draft`() {
        val request = CreateContentRequest("Test", "Content")
        val result = eventGateway.process(createMessage(request, null))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val response = (result as OperationResult.Success).payload as CreateContentResponse
        assertEquals("Anonymous", response.authorDisplayName)
        assertNull(response.authorId)
        assertEquals(PostStatus.DRAFT, response.status)
    }

    @Test
    fun `unverified email returns error`() {
        val request = CreateContentRequest("Test", "Content")
        val result = eventGateway.process(createMessage(request, unverifiedUser))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Email verification required", (result as OperationResult.Error).message)
    }

    @Test
    fun `blank title returns error`() {
        val request = CreateContentRequest("", "Content")
        val result = eventGateway.process(createMessage(request, verifiedUser))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Title is required", (result as OperationResult.Error).message)
    }

    @Test
    fun `blank markdownSource returns error`() {
        val request = CreateContentRequest("Title", "")
        val result = eventGateway.process(createMessage(request, verifiedUser))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Content is required", (result as OperationResult.Error).message)
    }

    @Test
    fun `slug saved to repository`() {
        val request = CreateContentRequest("Slug Test", "Content for slug test.")
        val result = eventGateway.process(createMessage(request, verifiedUser))

        val response = (result as OperationResult.Success).payload as CreateContentResponse
        val slug = slugRepository.findCanonical(response.id)
        assertNotNull(slug)
        assertEquals(response.slug, slug!!.path)
        assertTrue(slug.canonical)
    }

    @Test
    fun `create with tags creates tag entities and associations`() {
        val request =
            CreateContentRequest("Tagged Post", "Content.", tags = listOf("kotlin", "spring"))
        val result = eventGateway.process(createMessage(request, verifiedUser))

        val response = (result as OperationResult.Success).payload as CreateContentResponse
        assertEquals(listOf("kotlin", "spring"), response.tags)

        val postTags = postTagRepository.findByPost(response.id)
        assertEquals(2, postTags.size)

        assertNotNull(tagRepository.findByName("kotlin"))
        assertNotNull(tagRepository.findByName("spring"))
    }

    @Test
    fun `create with unknown tags auto-creates them`() {
        val request = CreateContentRequest("New Tags", "Content.", tags = listOf("brandnewtag"))
        val result = eventGateway.process(createMessage(request, verifiedUser))

        val response = (result as OperationResult.Success).payload as CreateContentResponse
        assertEquals(listOf("brandnewtag"), response.tags)

        val tag = tagRepository.findByName("brandnewtag")
        assertNotNull(tag)
        assertEquals("brandnewtag", tag!!.slug)
    }

    @Test
    fun `create with categoryIds creates associations`() {
        val category = categoryRepository.save(Category(name = "JVM", slug = "jvm"))
        val request =
            CreateContentRequest("Categorized Post", "Content.", categoryIds = listOf(category.id))
        val result = eventGateway.process(createMessage(request, verifiedUser))

        val response = (result as OperationResult.Success).payload as CreateContentResponse
        assertEquals(listOf("JVM"), response.categories)
    }

    @Test
    fun `create with nonexistent categoryId silently skips it`() {
        val request =
            CreateContentRequest("Missing Cat", "Content.", categoryIds = listOf(UUID.randomUUID()))
        val result = eventGateway.process(createMessage(request, verifiedUser))

        val response = (result as OperationResult.Success).payload as CreateContentResponse
        assertTrue(response.categories.isEmpty())
    }

    @Test
    fun `post in system category gets bare slug without date prefix`() {
        val pagesCategory = categoryRepository.findByName("_pages")!!
        val request =
            CreateContentRequest(
                "About",
                "About this site.",
                categoryIds = listOf(pagesCategory.id),
            )
        val result = eventGateway.process(createMessage(request, verifiedUser))

        val response = (result as OperationResult.Success).payload as CreateContentResponse
        assertEquals("about", response.slug)
    }

    @Test
    fun `post in normal category gets dated slug`() {
        val category = categoryRepository.save(Category(name = "News", slug = "news"))
        val request =
            CreateContentRequest(
                "Big News",
                "Something happened.",
                categoryIds = listOf(category.id),
            )
        val result = eventGateway.process(createMessage(request, verifiedUser))

        val response = (result as OperationResult.Success).payload as CreateContentResponse
        assertTrue(response.slug.matches(Regex("\\d{4}/\\d{2}/big-news")))
    }

    @Test
    // @lat: [[operations#User-Facing Operations#Blog Notifications#Submission Emails]]
    fun `non-admin submission notifies active admins by email`() {
        val request = CreateContentRequest("Needs Review", "Please review this draft.")

        val result = eventGateway.process(createMessage(request, verifiedUser))

        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals(2, capturingMailSubscriber.received.size)
        assertEquals(
            setOf("editor@test.com", "owner@test.com"),
            capturingMailSubscriber.received.map { it.second.replyTo }.toSet(),
        )
        val bodies =
            capturingMailSubscriber.received.map { (message, _) ->
                (message as OperationResult.Success).payload.toString()
            }
        assertTrue(
            bodies.also { logger.info(it.joinToString("\n")) }.all { it.contains("Needs Review") }
        )
        assertTrue(
            bodies.all { it.contains("http://localhost:3001/posts/$yearMonth/needs-review") }
        )
    }

    @Test
    fun `anonymous submission notifies active admins by email`() {
        val request = CreateContentRequest("Anonymous Draft", "Anonymous content.")

        val result = eventGateway.process(createMessage(request, null))

        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals(2, capturingMailSubscriber.received.size)
        assertEquals(
            setOf("editor@test.com", "owner@test.com"),
            capturingMailSubscriber.received.map { it.second.replyTo }.toSet(),
        )
        val bodies =
            capturingMailSubscriber.received.map { (message, _) ->
                (message as OperationResult.Success).payload.toString()
            }
        assertTrue(
            bodies.all { it.contains("http://localhost:3001/posts/$yearMonth/anonymous-draft") }
        )
    }

    @Test
    fun `admin-authored submission does not notify admins`() {
        val request = CreateContentRequest("Admin Draft", "Admin content.")

        val result = eventGateway.process(createMessage(request, adminUser))

        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals(0, capturingMailSubscriber.received.size)
    }
}
