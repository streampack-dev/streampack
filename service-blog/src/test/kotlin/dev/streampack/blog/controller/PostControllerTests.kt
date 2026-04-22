/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.blog.entity.Category
import dev.streampack.blog.entity.Post
import dev.streampack.blog.entity.PostCategory
import dev.streampack.blog.entity.PostTag
import dev.streampack.blog.entity.Slug
import dev.streampack.blog.entity.Tag
import dev.streampack.blog.model.PostStatus
import dev.streampack.blog.repository.CategoryRepository
import dev.streampack.blog.repository.PostCategoryRepository
import dev.streampack.blog.repository.PostRepository
import dev.streampack.blog.repository.PostTagRepository
import dev.streampack.blog.repository.SlugRepository
import dev.streampack.blog.repository.TagRepository
import dev.streampack.core.entity.User
import dev.streampack.core.model.Role
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.JwtService
import dev.streampack.temperature.service.TemperatureService
import dev.streampack.test.ResetDatabaseBeforeEach
import dev.streampack.test.TestChannelConfiguration
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put

/** Integration tests for public and authenticated post endpoints */
@SpringBootTest
@AutoConfigureMockMvc
@ResetDatabaseBeforeEach
@Import(TestChannelConfiguration::class)
@TestPropertySource(properties = ["streampack.blog.anonymous-submission=true"])
class PostControllerTests {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var slugRepository: SlugRepository
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var categoryRepository: CategoryRepository
    @Autowired lateinit var postCategoryRepository: PostCategoryRepository
    @Autowired lateinit var tagRepository: TagRepository
    @Autowired lateinit var postTagRepository: PostTagRepository
    @Autowired lateinit var temperatureService: TemperatureService

    private lateinit var verifiedUser: User
    private lateinit var verifiedUserToken: String
    private lateinit var unverifiedUser: User
    private lateinit var unverifiedUserToken: String
    private lateinit var publishedPost: Post
    private lateinit var slugPath: String

    @BeforeEach
    fun setUp() {
        val now = Instant.now()

        verifiedUser =
            userRepository.save(
                User(
                    username = "poster",
                    email = "poster@test.com",
                    displayName = "Test Poster",
                    emailVerified = true,
                    role = Role.USER,
                )
            )
        verifiedUserToken = jwtService.generateToken(verifiedUser.toUserPrincipal())

        unverifiedUser =
            userRepository.save(
                User(
                    username = "unverifiedposter",
                    email = "unverifiedposter@test.com",
                    displayName = "Unverified Poster",
                    emailVerified = false,
                    role = Role.USER,
                )
            )
        unverifiedUserToken = jwtService.generateToken(unverifiedUser.toUserPrincipal())

        publishedPost =
            postRepository.save(
                Post(
                    title = "Published Post",
                    markdownSource = "Published content.",
                    renderedHtml = "<p>Published content.</p>",
                    excerpt = "Published content.",
                    status = PostStatus.APPROVED,
                    publishedAt = now.minus(1, ChronoUnit.HOURS),
                    author = verifiedUser,
                    createdAt = now,
                    updatedAt = now,
                )
            )

        slugPath = "2026/01/published-post"
        slugRepository.save(
            Slug(path = slugPath, post = publishedPost, canonical = true, createdAt = now)
        )
    }

    // --- GET /posts ---

    @Test
    fun `GET posts returns paginated list`() {
        mockMvc.get("/posts").andExpect {
            status { isOk() }
            jsonPath("$.posts") { isArray() }
            jsonPath("$.page") { value(0) }
            jsonPath("$.totalCount") { isNumber() }
        }
    }

    @Test
    fun `GET posts popular defaults to three posts ordered by hit temperature`() {
        val now = Instant.now()
        val posts =
            (1..4).map { index ->
                val post =
                    postRepository.save(
                        Post(
                            title = "Popular Post $index",
                            markdownSource = "popular $index",
                            renderedHtml = "<p>popular $index</p>",
                            excerpt = "popular $index",
                            status = PostStatus.APPROVED,
                            publishedAt = now.minus(index.toLong(), ChronoUnit.HOURS),
                            author = verifiedUser,
                        )
                    )
                slugRepository.save(
                    Slug(path = "2026/01/popular-post-$index", post = post, canonical = true)
                )
                post
            }

        temperatureService.accrue("blog.post", posts[0].id.toString(), "hit", positiveDelta = 1L)
        temperatureService.accrue("blog.post", posts[1].id.toString(), "hit", positiveDelta = 4L)
        temperatureService.accrue("blog.post", posts[2].id.toString(), "hit", positiveDelta = 3L)
        temperatureService.accrue("blog.post", posts[3].id.toString(), "hit", positiveDelta = 2L)

        mockMvc.get("/posts/popular").andExpect {
            status { isOk() }
            jsonPath("$.posts.length()") { value(3) }
            jsonPath("$.posts[0].title") { value("Popular Post 2") }
            jsonPath("$.posts[1].title") { value("Popular Post 3") }
            jsonPath("$.posts[2].title") { value("Popular Post 4") }
            jsonPath("$.page") { value(0) }
            jsonPath("$.totalCount") { value(4) }
        }
    }

    // --- GET /posts/{year}/{month}/{slug} ---

    @Test
    fun `GET post by slug returns detail`() {
        mockMvc.get("/posts/$slugPath").andExpect {
            status { isOk() }
            jsonPath("$.id") { value(publishedPost.id.toString()) }
            jsonPath("$.title") { value("Published Post") }
            jsonPath("$.renderedHtml") { isNotEmpty() }
        }
        waitForAccessCount(publishedPost.id, 1)
    }

    @Test
    fun `GET nonexistent slug returns 404`() {
        mockMvc.get("/posts/2026/01/no-such-post").andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("Post not found") }
        }
    }

    @Test
    fun `POST post access returns accepted`() {
        mockMvc.post("/posts/${publishedPost.id}/access").andExpect { status { isAccepted() } }
    }

    @Test
    fun `GET post by id returns detail and increments access count`() {
        mockMvc.get("/posts/${publishedPost.id}").andExpect {
            status { isOk() }
            jsonPath("$.id") { value(publishedPost.id.toString()) }
            jsonPath("$.title") { value("Published Post") }
        }
        waitForAccessCount(publishedPost.id, 1)
    }

    // --- GET /posts/search ---

    @Test
    fun `GET search returns results`() {
        mockMvc.get("/posts/search?q=Published").andExpect {
            status { isOk() }
            jsonPath("$.posts") { isArray() }
        }
    }

    // --- POST /posts ---

    @Test
    fun `POST creates draft and returns 201`() {
        mockMvc
            .post("/posts") {
                contentType = MediaType.APPLICATION_JSON
                header("Authorization", "Bearer $verifiedUserToken")
                content =
                    """{"title":"My New Post","markdownSource":"Hello world.","formLoadedAt":${System.currentTimeMillis() - 5000}}"""
            }
            .andExpect {
                status { isCreated() }
                jsonPath("$.title") { value("My New Post") }
                jsonPath("$.status") { value("DRAFT") }
                jsonPath("$.authorDisplayName") { value("Test Poster") }
                jsonPath("$.id") { isNotEmpty() }
                jsonPath("$.slug") { isNotEmpty() }
            }
    }

    @Test
    fun `POST anonymous submission creates draft`() {
        mockMvc
            .post("/posts") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"title":"Community Post","markdownSource":"Anonymous content.","formLoadedAt":${System.currentTimeMillis() - 5000}}"""
            }
            .andExpect {
                status { isCreated() }
                jsonPath("$.title") { value("Community Post") }
                jsonPath("$.status") { value("DRAFT") }
                jsonPath("$.authorDisplayName") { value("Anonymous") }
                jsonPath("$.id") { isNotEmpty() }
            }
    }

    @Test
    fun `POST with unverified email returns 400`() {
        mockMvc
            .post("/posts") {
                contentType = MediaType.APPLICATION_JSON
                header("Authorization", "Bearer $unverifiedUserToken")
                content =
                    """{"title":"My Post","markdownSource":"Content.","formLoadedAt":${System.currentTimeMillis() - 5000}}"""
            }
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.detail") { value("Email verification required") }
            }
    }

    @Test
    fun `POST with blank title returns 400`() {
        mockMvc
            .post("/posts") {
                contentType = MediaType.APPLICATION_JSON
                header("Authorization", "Bearer $verifiedUserToken")
                content =
                    """{"title":"","markdownSource":"Content.","formLoadedAt":${System.currentTimeMillis() - 5000}}"""
            }
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.detail") { value("Title is required") }
            }
    }

    private fun waitForAccessCount(postId: java.util.UUID, expected: Long) {
        repeat(40) {
            val current = postRepository.findById(postId).orElseThrow().accessCount
            if (current == expected) return
            Thread.sleep(25)
        }
        assertEquals(expected, postRepository.findById(postId).orElseThrow().accessCount)
    }

    @Test
    fun `POST derive-tags suggests heuristic tags without authentication`() {
        mockMvc
            .post("/posts/derive-tags") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"title":"Kotlin JVM Internals","markdownSource":"Kotlin on the JVM is practical. JVM tuning and Kotlin tooling matter.","existingTags":["java"]}"""
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.tags") { isArray() }
                jsonPath("$.tags[0]") { isNotEmpty() }
            }
    }

    @Test
    fun `POST derive-tags with blank content returns 400`() {
        mockMvc
            .post("/posts/derive-tags") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"title":"Anything","markdownSource":"","existingTags":["java"]}"""
            }
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.detail") { value("Content is required") }
            }
    }

    @Test
    fun `POST derive-summary authenticated returns summary`() {
        mockMvc
            .post("/posts/derive-summary") {
                contentType = MediaType.APPLICATION_JSON
                header("Authorization", "Bearer $verifiedUserToken")
                content =
                    """{"title":"Summary Title","markdownSource":"Sentence one. Sentence two. Sentence three."}"""
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.summary") { isNotEmpty() }
            }
    }

    @Test
    fun `POST derive-summary unauthenticated returns 401`() {
        mockMvc
            .post("/posts/derive-summary") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"title":"Summary Title","markdownSource":"Sentence one."}"""
            }
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.detail") { value("Authentication required") }
            }
    }

    // --- PUT /posts/{id} ---

    @Test
    fun `PUT by non-admin author returns 403`() {
        val draftPost =
            postRepository.save(
                Post(
                    title = "Draft Post",
                    markdownSource = "Original.",
                    renderedHtml = "<p>Original.</p>",
                    excerpt = "Original.",
                    status = PostStatus.DRAFT,
                    author = verifiedUser,
                    createdAt = Instant.now(),
                    updatedAt = Instant.now(),
                )
            )

        mockMvc
            .put("/posts/${draftPost.id}") {
                contentType = MediaType.APPLICATION_JSON
                header("Authorization", "Bearer $verifiedUserToken")
                content = """{"title":"Updated Title","markdownSource":"Updated content."}"""
            }
            .andExpect {
                status { isForbidden() }
                jsonPath("$.detail") { value("Not authorized to edit this post") }
            }
    }

    @Test
    fun `PUT unauthenticated returns 401`() {
        mockMvc
            .put("/posts/${publishedPost.id}") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"title":"Updated Title","markdownSource":"Updated content."}"""
            }
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.detail") { value("Authentication required") }
            }
    }

    // --- Honeypot and timing checks ---

    @Test
    fun `POST with honeypot field populated returns fake 201`() {
        mockMvc
            .post("/posts") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"title":"Spam Post","markdownSource":"Buy stuff.","website":"http://spam.example.com","formLoadedAt":${System.currentTimeMillis() - 5000}}"""
            }
            .andExpect {
                status { isCreated() }
                jsonPath("$.title") { value("Submitted") }
            }
    }

    @Test
    fun `POST submitted too quickly returns fake 201`() {
        val justNow = System.currentTimeMillis()
        mockMvc
            .post("/posts") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"title":"Fast Post","markdownSource":"Content.","formLoadedAt":$justNow}"""
            }
            .andExpect {
                status { isCreated() }
                jsonPath("$.title") { value("Submitted") }
            }
    }

    @Test
    fun `POST with null formLoadedAt returns fake 201`() {
        mockMvc
            .post("/posts") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"title":"No Timing","markdownSource":"Content."}"""
            }
            .andExpect {
                status { isCreated() }
                jsonPath("$.title") { value("Submitted") }
            }
    }

    // --- GET /posts?category= ---

    @Test
    fun `GET posts with category filter returns posts in that category`() {
        val category = categoryRepository.save(Category(name = "kotlin", slug = "kotlin"))
        postCategoryRepository.save(PostCategory(post = publishedPost, category = category))

        mockMvc.get("/posts?category=kotlin").andExpect {
            status { isOk() }
            jsonPath("$.posts.length()") { value(1) }
            jsonPath("$.posts[0].title") { value("Published Post") }
        }
    }

    @Test
    fun `GET posts with empty category returns empty list`() {
        categoryRepository.save(Category(name = "empty", slug = "empty"))

        mockMvc.get("/posts?category=empty").andExpect {
            status { isOk() }
            jsonPath("$.posts.length()") { value(0) }
            jsonPath("$.totalCount") { value(0) }
        }
    }

    @Test
    fun `GET posts with tag filter returns posts with that tag`() {
        val tag = tagRepository.save(Tag(name = "kotlin", slug = "kotlin"))
        postTagRepository.save(PostTag(post = publishedPost, tag = tag))

        mockMvc.get("/posts?tag=kotlin").andExpect {
            status { isOk() }
            jsonPath("$.posts.length()") { value(1) }
            jsonPath("$.posts[0].title") { value("Published Post") }
        }
    }

    @Test
    fun `GET posts with empty tag returns empty list`() {
        tagRepository.save(Tag(name = "empty-tag", slug = "empty-tag"))

        mockMvc.get("/posts?tag=empty-tag").andExpect {
            status { isOk() }
            jsonPath("$.posts.length()") { value(0) }
            jsonPath("$.totalCount") { value(0) }
        }
    }
}
