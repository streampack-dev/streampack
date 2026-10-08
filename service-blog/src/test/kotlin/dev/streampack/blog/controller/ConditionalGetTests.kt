/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.blog.entity.Category
import dev.streampack.blog.entity.Comment
import dev.streampack.blog.entity.Post
import dev.streampack.blog.entity.PostCategory
import dev.streampack.blog.entity.PostTag
import dev.streampack.blog.entity.Slug
import dev.streampack.taxonomy.entity.Tag
import dev.streampack.blog.model.PostStatus
import dev.streampack.blog.repository.CategoryRepository
import dev.streampack.blog.repository.CommentRepository
import dev.streampack.blog.repository.PostCategoryRepository
import dev.streampack.blog.repository.PostRepository
import dev.streampack.blog.repository.PostTagRepository
import dev.streampack.blog.repository.SlugRepository
import dev.streampack.taxonomy.repository.TagRepository
import dev.streampack.core.entity.User
import dev.streampack.core.model.Role
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.JwtService
import dev.streampack.test.ResetDatabaseBeforeEach
import dev.streampack.test.TestChannelConfiguration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.integration.channel.AbstractMessageChannel
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/**
 * Public GETs carry data-derived validators and answer matching conditional requests with 304
 * before any operation runs (#83).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ResetDatabaseBeforeEach
@Import(TestChannelConfiguration::class)
@TestPropertySource(properties = ["streampack.api.version.supported=2025-01-01"])
class ConditionalGetTests {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var slugRepository: SlugRepository
    @Autowired lateinit var commentRepository: CommentRepository
    @Autowired lateinit var tagRepository: TagRepository
    @Autowired lateinit var postTagRepository: PostTagRepository
    @Autowired lateinit var categoryRepository: CategoryRepository
    @Autowired lateinit var postCategoryRepository: PostCategoryRepository
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired @Qualifier("ingressChannel") lateinit var ingress: AbstractMessageChannel

    private val dispatched = AtomicInteger()
    private val counter =
        object : ChannelInterceptor {
            override fun preSend(message: Message<*>, channel: MessageChannel): Message<*> {
                dispatched.incrementAndGet()
                return message
            }
        }

    private lateinit var author: User
    private lateinit var post: Post
    private val path = "2026/01/etag-post"

    @BeforeEach
    fun setUp() {
        ingress.addInterceptor(counter)
        author =
            userRepository.save(
                User(
                    username = "etagger",
                    email = "etagger@test.com",
                    displayName = "ETag Author",
                    emailVerified = true,
                    role = Role.USER,
                )
            )
        post = publish("ETag Post", path, tags = listOf("jvm"))
    }

    @AfterEach
    fun tearDown() {
        ingress.removeInterceptor(counter)
    }

    private fun publish(
        title: String,
        slug: String,
        tags: List<String> = emptyList(),
        publishedAt: Instant = Instant.now().minus(1, ChronoUnit.HOURS),
        hiddenCategory: String? = null,
    ): Post {
        val now = Instant.now()
        val p =
            postRepository.save(
                Post(
                    title = title,
                    markdownSource = "$title body",
                    renderedHtml = "<p>$title body</p>",
                    excerpt = "$title excerpt",
                    status = PostStatus.APPROVED,
                    publishedAt = publishedAt,
                    author = author,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        slugRepository.save(Slug(path = slug, post = p, canonical = true, createdAt = now))
        tags.forEach { t ->
            val tag = tagRepository.findByName(t) ?: tagRepository.save(Tag(name = t, slug = t))
            postTagRepository.save(PostTag(post = p, tag = tag))
        }
        hiddenCategory?.let { c ->
            val cat = categoryRepository.save(Category(name = c, slug = c.trimStart('_')))
            postCategoryRepository.save(PostCategory(post = p, category = cat))
        }
        return p
    }

    private fun comment(on: Post, text: String) =
        commentRepository.save(
            Comment(
                post = on,
                author = author,
                markdownSource = text,
                renderedHtml = "<p>$text</p>",
            )
        )

    private fun etag(url: String, version: String? = null): String {
        val response =
            mockMvc
                .get(url) { version?.let { header("Accept-Version", it) } }
                .andExpect { status { isOk() } }
                .andReturn()
                .response
        val tag = response.getHeader("ETag")
        assertNotNull(tag, "ETag on $url")
        assertTrue(tag!!.startsWith("W/\""), "weak ETag on $url: $tag")
        return tag
    }

    /** A matching conditional GET is a bodiless 304, and no operation runs for it. */
    private fun assertNotModified(url: String, tag: String) {
        val before = dispatched.get()
        val response =
            mockMvc
                .get(url) { header("If-None-Match", tag) }
                .andExpect { status { isNotModified() } }
                .andReturn()
                .response
        assertEquals("", response.contentAsString, "304 body for $url")
        assertEquals(tag, response.getHeader("ETag"))
        assertEquals(before, dispatched.get(), "no operation dispatched for a 304 on $url")
    }

    private fun assertChanged(url: String, before: String) = assertNotEquals(before, etag(url), url)

    @Test
    fun `public GETs carry a weak ETag, no-cache and Vary, and answer a match with 304`() {
        // The dispatch counter must see ordinary requests, or "no dispatch on 304" proves nothing.
        val before = dispatched.get()
        etag("/posts")
        assertTrue(dispatched.get() > before, "a full GET dispatches an operation")

        for (url in
            listOf(
                "/posts",
                "/posts?tag=jvm",
                "/posts/search?q=etag",
                "/posts/$path",
                "/posts/$path/comments",
                "/taxonomy",
                "/categories",
            )) {
            val response = mockMvc.get(url).andReturn().response
            assertEquals(200, response.status, url)
            val cc = response.getHeader("Cache-Control").orEmpty()
            assertTrue(
                cc.contains("no-cache") && !cc.contains("no-store"),
                "$url Cache-Control: $cc",
            )
            assertTrue(
                response.getHeaders("Vary").joinToString(",").contains("Accept-Version"),
                "$url Vary",
            )
            assertNotModified(url, etag(url))
        }
    }

    @Test
    fun `the list ETag follows approvals, edits, deletes, retags, comments and scheduled posts`() {
        var tag = etag("/posts")

        val second = publish("Second", "2026/01/second")
        assertChanged("/posts", tag).also { tag = etag("/posts") }

        postRepository.save(
            second.copy(title = "Second, edited", updatedAt = Instant.now().plusSeconds(1))
        )
        assertChanged("/posts", tag).also { tag = etag("/posts") }

        comment(second, "first!")
        assertChanged("/posts", tag).also { tag = etag("/posts") }

        val scheduled =
            publish("Later", "2026/01/later", publishedAt = Instant.now().plus(1, ChronoUnit.DAYS))
        assertEquals(tag, etag("/posts"), "a scheduled post changes nothing until it's due")
        // Time passing, without the row changing: the post falls due.
        jdbc.update(
            "UPDATE posts SET published_at = now() - interval '1 minute' WHERE id = ?",
            scheduled.id,
        )
        assertChanged("/posts", tag).also { tag = etag("/posts") }

        postRepository.save(second.copy(deleted = true))
        assertChanged("/posts", tag)

        val tagged = etag("/posts?tag=jvm")
        postTagRepository.save(PostTag(post = scheduled, tag = tagRepository.findByName("jvm")!!))
        assertChanged("/posts?tag=jvm", tagged)
    }

    @Test
    fun `the article ETag follows edits and comments, and matches If-Modified-Since`() {
        val url = "/posts/$path"
        var tag = etag(url)

        postRepository.save(
            post.copy(renderedHtml = "<p>new</p>", updatedAt = Instant.now().plusSeconds(1))
        )
        assertChanged(url, tag).also { tag = etag(url) }

        val c = comment(post, "hello")
        assertChanged(url, tag).also { tag = etag(url) }
        val comments = etag("$url/comments")

        commentRepository.save(
            c.copy(renderedHtml = "<p>edited</p>", updatedAt = Instant.now().plusSeconds(2))
        )
        assertChanged(url, tag).also { tag = etag(url) }
        assertChanged("$url/comments", comments)

        commentRepository.save(c.copy(deleted = true))
        assertChanged(url, tag)

        val lastModified = mockMvc.get(url).andReturn().response.getHeader("Last-Modified")
        assertNotNull(lastModified)
        mockMvc
            .get(url) { header("If-Modified-Since", lastModified!!) }
            .andExpect { status { isNotModified() } }
    }

    @Test
    fun `search and taxonomy ETags follow new posts and retags`() {
        val search = etag("/posts/search?q=zeppelin")
        val taxonomy = etag("/taxonomy")

        val z = publish("Zeppelin", "2026/01/zeppelin")
        assertChanged("/posts/search?q=zeppelin", search)
        val afterPost = etag("/taxonomy")
        assertNotEquals(taxonomy, afterPost)

        postTagRepository.save(
            PostTag(post = z, tag = tagRepository.save(Tag(name = "airships", slug = "airships")))
        )
        assertChanged("/taxonomy", afterPost)
    }

    @Test
    fun `pages carry validators too`() {
        publish("About", "about", hiddenCategory = "_pages")

        assertNotModified("/pages/about", etag("/pages/about"))
    }

    @Test
    fun `ETags differ across API versions`() {
        assertNotEquals(etag("/posts", "2025-01-01"), etag("/posts"))
    }

    @Test
    fun `popular posts carry no validator`() {
        assertNull(mockMvc.get("/posts/popular").andReturn().response.getHeader("ETag"))
    }

    @Test
    fun `authenticated responses are private and never conditional`() {
        val token = jwtService.generateToken(author.toUserPrincipal())
        val anonymous = etag("/posts/$path")

        val response =
            mockMvc
                .get("/posts/$path") {
                    header("Authorization", "Bearer $token")
                    header("If-None-Match", anonymous)
                }
                .andExpect { status { isOk() } }
                .andReturn()
                .response

        assertNull(response.getHeader("ETag"))
        assertTrue(response.getHeader("Cache-Control").orEmpty().contains("no-store"))
        assertTrue(response.getHeader("Cache-Control").orEmpty().contains("private"))
    }
}
