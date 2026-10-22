/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.blog.entity.Category
import dev.streampack.blog.entity.Post
import dev.streampack.blog.entity.PostCategory
import dev.streampack.blog.entity.PostTag
import dev.streampack.blog.entity.Slug
import dev.streampack.blog.model.PostStatus
import dev.streampack.blog.repository.CategoryRepository
import dev.streampack.blog.repository.PostCategoryRepository
import dev.streampack.blog.repository.PostRepository
import dev.streampack.blog.repository.PostTagRepository
import dev.streampack.blog.repository.SlugRepository
import dev.streampack.core.entity.User
import dev.streampack.core.model.Role
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.JwtService
import dev.streampack.taxonomy.entity.Tag
import dev.streampack.taxonomy.repository.TagRepository
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
    @Autowired lateinit var factoidRepository: dev.streampack.factoid.repository.FactoidRepository

    @Autowired
    lateinit var factoidAttributeRepository:
        dev.streampack.factoid.repository.FactoidAttributeRepository

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
                ),
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
                ),
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
                ),
            )

        slugPath = "2026/01/published-post"
        slugRepository.save(
            Slug(path = slugPath, post = publishedPost, canonical = true, createdAt = now),
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
                        ),
                    )
                slugRepository.save(
                    Slug(path = "2026/01/popular-post-$index", post = post, canonical = true),
                )
                post
            }

        temperatureService.accrue("blog.post", posts[0].id.toString(), "hit", positiveDelta = 1L)
        temperatureService.accrue("blog.post", posts[1].id.toString(), "hit", positiveDelta = 4L)
        temperatureService.accrue("blog.post", posts[2].id.toString(), "hit", positiveDelta = 3L)
        temperatureService.accrue("blog.post", posts[3].id.toString(), "hit", positiveDelta = 2L)
        val scoresBefore = posts.associate { post ->
            post.id to temperatureService.score("blog.post", post.id.toString(), "hit").netScore
        }

        mockMvc.get("/posts/popular").andExpect {
            status { isOk() }
            jsonPath("$.posts.length()") { value(3) }
            jsonPath("$.posts[0].title") { value("Popular Post 2") }
            jsonPath("$.posts[1].title") { value("Popular Post 3") }
            jsonPath("$.posts[2].title") { value("Popular Post 4") }
            jsonPath("$.page") { value(0) }
            jsonPath("$.totalCount") { value(4) }
        }

        val scoresAfter = posts.associate { post ->
            post.id to temperatureService.score("blog.post", post.id.toString(), "hit").netScore
        }
        assertEquals(scoresBefore, scoresAfter)
    }

    // --- GET /posts/{year}/{month}/{slug} ---

    @Test
    fun `GET post by slug returns detail without recording access`() {
        mockMvc.get("/posts/$slugPath").andExpect {
            status { isOk() }
            jsonPath("$.id") { value(publishedPost.id.toString()) }
            jsonPath("$.title") { value("Published Post") }
            jsonPath("$.renderedHtml") { isNotEmpty() }
        }
        waitForAccessCount(publishedPost.id, 0)
        assertEquals(
            0.0,
            temperatureService.score("blog.post", publishedPost.id.toString(), "hit").netScore,
        )
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
        waitForAccessCount(publishedPost.id, 1)
        assertEquals(
            1.0,
            temperatureService.score("blog.post", publishedPost.id.toString(), "hit").netScore,
        )
    }

    @Test
    fun `GET post by id returns detail without recording access`() {
        mockMvc.get("/posts/${publishedPost.id}").andExpect {
            status { isOk() }
            jsonPath("$.id") { value(publishedPost.id.toString()) }
            jsonPath("$.title") { value("Published Post") }
        }
        waitForAccessCount(publishedPost.id, 0)
        assertEquals(
            0.0,
            temperatureService.score("blog.post", publishedPost.id.toString(), "hit").netScore,
        )
    }

    // --- GET /posts/search ---

    @Test
    fun `GET search returns results`() {
        mockMvc.get("/posts/search?q=Published").andExpect {
            status { isOk() }
            jsonPath("$.posts") { isArray() }
        }
    }

    @Test
    fun `GET search says why each result matched`() {
        mockMvc.get("/posts/search?q=publishing").andExpect {
            status { isOk() }
            jsonPath("$.posts[0].title") { value("Published Post") }
            jsonPath("$.posts[0].match.source") { value("body") }
            jsonPath("$.posts[0].match.text") { value("Published content") }
            jsonPath("$.posts[0].match.marks[0].start") { value(0) }
            jsonPath("$.posts[0].match.marks[0].end") { value(9) }
        }
    }

    @Test
    fun `GET search with a quoted word matches it exactly`() {
        mockMvc.get("/posts/search?q=\"publishing\"").andExpect {
            status { isOk() }
            jsonPath("$.totalCount") { value(0) }
        }
        mockMvc.get("/posts/search?q=\"published\"").andExpect {
            status { isOk() }
            jsonPath("$.posts[0].title") { value("Published Post") }
        }
    }

    @Test
    fun `GET search refuses a query that's too long`() {
        mockMvc.get("/posts/search?q=" + "a".repeat(201)).andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("Search query is too long: at most 200 characters") }
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

    @Test
    fun `POST derive-factoids authenticated returns the factoids mentioned and the links to none`() {
        val factoid =
            factoidRepository.save(
                dev.streampack.factoid.entity.Factoid(selector = "flywheelia", updatedBy = "test"),
            )
        factoidAttributeRepository.save(
            dev.streampack.factoid.entity.FactoidAttribute(
                factoid = factoid,
                attributeType = dev.streampack.factoid.model.FactoidAttributeType.TEXT,
                attributeValue = "a made-up framework.",
            ),
        )
        try {
            mockMvc
                .post("/posts/derive-factoids") {
                    contentType = MediaType.APPLICATION_JSON
                    header("Authorization", "Bearer $verifiedUserToken")
                    content =
                        """{"markdownSource":"Flywheelia is new, unlike [[nosuchfactoidyet]]."}"""
                }
                .andExpect {
                    status { isOk() }
                    jsonPath("$.mentions[0].selector") { value("flywheelia") }
                    jsonPath("$.mentions[0].definition") { value("a made-up framework.") }
                    jsonPath("$.missing[0].selector") { value("nosuchfactoidyet") }
                }
        } finally {
            factoidAttributeRepository.deleteAll(
                factoidAttributeRepository.findByFactoidSelectorIgnoreCase("flywheelia"),
            )
            factoidRepository.delete(factoid)
        }
    }

    @Test
    fun `POST derive-factoids unauthenticated returns 401`() {
        mockMvc
            .post("/posts/derive-factoids") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"markdownSource":"Gradle."}"""
            }
            .andExpect { status { isUnauthorized() } }
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
                ),
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

    // --- Posts by author (#273) ---

    /** A second author, with one post published, so lists by author can tell them apart */
    private fun otherAuthorWithPost(): User {
        val now = Instant.now()
        val other =
            userRepository.save(
                User(
                    username = "other",
                    email = "other@test.com",
                    displayName = "Other Author",
                    emailVerified = true,
                    role = Role.ADMIN,
                ),
            )
        val post =
            postRepository.save(
                Post(
                    title = "Other Post",
                    markdownSource = "Other content.",
                    renderedHtml = "<p>Other content.</p>",
                    status = PostStatus.APPROVED,
                    publishedAt = now.minus(2, ChronoUnit.HOURS),
                    author = other,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        slugRepository.save(
            Slug(path = "2026/01/other-post", post = post, canonical = true, createdAt = now),
        )
        return other
    }

    /** A post by [verifiedUser] in [status], published at [publishedAt], and maybe deleted */
    private fun ownPost(
        title: String,
        status: PostStatus,
        publishedAt: Instant?,
        deleted: Boolean = false,
    ): Post {
        val now = Instant.now()
        return postRepository.save(
            Post(
                title = title,
                markdownSource = "$title.",
                renderedHtml = "<p>$title.</p>",
                status = status,
                publishedAt = publishedAt,
                author = verifiedUser,
                deleted = deleted,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    @Test
    fun `GET posts by author lists only that author's published posts`() {
        val other = otherAuthorWithPost()
        ownPost("A Draft", PostStatus.DRAFT, null)

        mockMvc.get("/posts?author=${verifiedUser.id}").andExpect {
            status { isOk() }
            jsonPath("$.posts.length()") { value(1) }
            jsonPath("$.posts[0].title") { value("Published Post") }
            jsonPath("$.totalCount") { value(1) }
        }
        mockMvc.get("/posts?author=${other.id}").andExpect {
            status { isOk() }
            jsonPath("$.posts.length()") { value(1) }
            jsonPath("$.posts[0].title") { value("Other Post") }
        }
    }

    @Test
    fun `GET posts by an unknown author returns an empty list`() {
        mockMvc.get("/posts?author=00000000-0000-0000-0000-000000000000").andExpect {
            status { isOk() }
            jsonPath("$.posts.length()") { value(0) }
            jsonPath("$.totalCount") { value(0) }
        }
    }

    @Test
    fun `GET posts with an author that is not a UUID returns 400`() {
        mockMvc.get("/posts?author=poster").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `GET posts by author with a tag or category returns 400`() {
        mockMvc.get("/posts?author=${verifiedUser.id}&tag=kotlin").andExpect {
            status { isBadRequest() }
        }
        mockMvc.get("/posts?author=${verifiedUser.id}&category=kotlin").andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `list entries and details carry the author's id and role`() {
        otherAuthorWithPost()

        mockMvc.get("/posts").andExpect {
            status { isOk() }
            jsonPath("$.posts[0].authorId") { value(verifiedUser.id.toString()) }
            jsonPath("$.posts[0].authorRole") { value("USER") }
            jsonPath("$.posts[1].authorRole") { value("ADMIN") }
        }
        mockMvc.get("/posts/$slugPath").andExpect {
            status { isOk() }
            jsonPath("$.authorRole") { value("USER") }
        }
    }

    @Test
    fun `GET auth posts lists the caller's own posts in every state`() {
        otherAuthorWithPost()
        val now = Instant.now()
        ownPost("A Draft", PostStatus.DRAFT, null)
        ownPost("Scheduled Post", PostStatus.APPROVED, now.plus(1, ChronoUnit.DAYS))
        ownPost("Deleted Draft", PostStatus.DRAFT, null, deleted = true)

        mockMvc
            .get("/auth/posts") { header("Authorization", "Bearer $verifiedUserToken") }
            .andExpect {
                status { isOk() }
                jsonPath("$.totalCount") { value(3) }
                jsonPath("$.posts[?(@.title == 'A Draft')].state") { value("DRAFT") }
                jsonPath("$.posts[?(@.title == 'Scheduled Post')].state") { value("SCHEDULED") }
                jsonPath("$.posts[?(@.title == 'Published Post')].state") { value("PUBLISHED") }
                jsonPath("$.posts[?(@.title == 'Other Post')]") { isEmpty() }
                jsonPath("$.posts[?(@.title == 'Deleted Draft')]") { isEmpty() }
            }
    }

    @Test
    fun `GET auth posts unauthenticated returns 401`() {
        mockMvc.get("/auth/posts").andExpect { status { isUnauthorized() } }
    }
}
