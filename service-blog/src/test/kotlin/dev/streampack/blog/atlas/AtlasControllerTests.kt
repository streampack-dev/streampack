/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.atlas

import com.jayway.jsonpath.JsonPath
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
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.JwtService
import dev.streampack.taxonomy.entity.Tag
import dev.streampack.taxonomy.repository.TagRepository
import dev.streampack.test.ResetDatabaseBeforeEach
import dev.streampack.test.TestChannelConfiguration
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.messaging.support.MessageBuilder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * The Atlas over HTTP (ui-pudl#184): the stored map with the taxonomy's counts, laid out once,
 * newcomers placed without moving anything, unused tags left off but kept, and a relayout only an
 * admin can ask for.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ResetDatabaseBeforeEach
@Import(TestChannelConfiguration::class)
class AtlasControllerTests {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var tagRepository: TagRepository
    @Autowired lateinit var postTagRepository: PostTagRepository
    @Autowired lateinit var slugRepository: SlugRepository
    @Autowired lateinit var categoryRepository: CategoryRepository
    @Autowired lateinit var postCategoryRepository: PostCategoryRepository
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var jdbc: JdbcTemplate

    private lateinit var author: User
    private val tags = HashMap<String, Tag>()
    private val posts = HashMap<String, Post>()

    private fun teach(text: String) {
        eventGateway.process(
            MessageBuilder.withPayload(text)
                .setHeader(
                    Provenance.HEADER,
                    Provenance(protocol = Protocol.CONSOLE, serviceId = "test", replyTo = "local"),
                )
                .setHeader("nick", "tester")
                .build()
        )
    }

    private fun tag(name: String): Tag =
        // Factoid tags taught first are tags already (#140): the vocabulary creates them.
        tags.getOrPut(name) {
            tagRepository.findByName(name) ?: tagRepository.save(Tag(name = name, slug = name))
        }

    private fun post(
        slug: String,
        vararg tagNames: String,
        status: PostStatus = PostStatus.APPROVED,
        publishedAt: Instant = Instant.now().minusSeconds(60),
        deleted: Boolean = false,
    ): Post {
        val post =
            postRepository.save(
                Post(
                    title = "Post $slug",
                    markdownSource = "Body",
                    renderedHtml = "<p>Body</p>",
                    status = status,
                    publishedAt = publishedAt,
                    deleted = deleted,
                    author = author,
                )
            )
        slugRepository.save(Slug(path = "2026/10/$slug", post = post, canonical = true))
        tagNames.forEach { postTagRepository.save(PostTag(post = post, tag = tag(it))) }
        posts[slug] = post
        return post
    }

    @BeforeEach
    fun setUp() {
        author =
            userRepository.save(
                User(
                    username = "atlas-author",
                    email = "atlas@author.test",
                    displayName = "Atlas Author",
                    emailVerified = true,
                    role = Role.USER,
                )
            )
        post("one", "java", "spring", "jvm")
        post("two", "java", "spring")
        post("three", "Java", "spring")
        post("kotlin", "kotlin", "jvm")
        post("mind", "psychology", "leadership")
        post("mind-2", "psychology", "leadership")
        // Not counted: a draft, a deleted post, a post not yet due, a post in a hidden category.
        post("draft", "drafty", "java", status = PostStatus.DRAFT)
        post("gone", "gone", "java", deleted = true)
        post("later", "later", "java", publishedAt = Instant.now().plus(Duration.ofDays(2)))
        val page = post("page", "secret", "java")
        postCategoryRepository.save(
            PostCategory(
                post = page,
                category = categoryRepository.save(Category(name = "_pages", slug = "pages")),
            )
        )
        teach("auth=who you are")
        teach("auth.tags=auth,http")
        teach("tls=transport security")
        teach("tls.tags=auth, http")
        teach("hotspot=a JVM")
        teach("hotspot.tags=java,jvm")
        teach("rpg=report program generator")
        teach("rpg.tags=cobol-ish") // written as `cobol ish` (#140)
    }

    private fun atlas(): String =
        mockMvc.get("/atlas").andExpect { status { isOk() } }.andReturn().response.contentAsString

    private fun positions(body: String): Map<String, Pair<Double, Double>> {
        val places: List<Map<String, Any>> = JsonPath.read(body, "$.places")
        return places.associate {
            it["tag"] as String to
                ((it["x"] as Number).toDouble() to (it["y"] as Number).toDouble())
        }
    }

    private fun placeOf(body: String, tag: String): Map<String, Any> {
        val places: List<Map<String, Any>> = JsonPath.read(body, "$.places[?(@.tag == '$tag')]")
        return places.single()
    }

    private fun stored(): Map<String, Pair<Double, Double>> =
        jdbc
            .query("SELECT tag, x, y FROM atlas_place") { rs, _ ->
                rs.getString("tag") to (rs.getDouble("x") to rs.getDouble("y"))
            }
            .toMap()

    private fun placedAt(): Map<String, Instant> =
        jdbc
            .query("SELECT tag, placed_at FROM atlas_place") { rs, _ ->
                rs.getString("tag") to rs.getTimestamp("placed_at").toInstant()
            }
            .toMap()

    private fun token(role: Role): String =
        jwtService.generateToken(
            userRepository
                .save(
                    User(
                        username = "atlas-${role.name.lowercase()}",
                        email = "${role.name.lowercase()}@atlas.test",
                        displayName = role.name,
                        emailVerified = true,
                        role = role,
                    )
                )
                .toUserPrincipal()
        )

    @Test
    fun `the map has regions and places with positions, counts, kinds, sizes and pins`() {
        mockMvc.get("/atlas").andExpect {
            status { isOk() }
            header { exists("ETag") }
            jsonPath("$.regions[0].id") { isNumber() }
            jsonPath("$.regions[0].name") { isString() }
            jsonPath("$.regions[0].x") { isNumber() }
            jsonPath("$.regions[0].y") { isNumber() }
            jsonPath("$.regions[0].r") { isNumber() }
            jsonPath("$.regions[0].uncharted") { value(false) }
            jsonPath("$.bounds.width") { isNumber() }
            jsonPath("$.laidOutAt") { isString() }
        }
        val body = atlas()

        val java = placeOf(body, "java")
        assertThat(java["kind"]).isEqualTo("shared")
        assertThat(java["articleCount"]).isEqualTo(3)
        assertThat(java["factoidCount"]).isEqualTo(1)
        assertThat((java["r"] as Number).toDouble()).isEqualTo(round1(4.0 + 2.6 * 2.0))
        assertThat((java["labelSize"] as Number).toDouble()).isEqualTo(12.0)
        assertThat(java["minor"]).isEqualTo(false)
        assertThat(placeOf(body, "psychology")["kind"]).isEqualTo("articles")
        assertThat(placeOf(body, "auth")["kind"]).isEqualTo("factoids")

        val pins: List<Map<String, Any>> =
            JsonPath.read(body, "$.places[?(@.tag == 'java')].pins[*]")
        assertThat(pins.filter { it["kind"] == "article" }.map { it["slug"] })
            .containsExactlyInAnyOrder("2026/10/one", "2026/10/two", "2026/10/three")
        val factoid = pins.single { it["kind"] == "factoid" }
        assertThat(factoid["selector"]).isEqualTo("hotspot")
        assertThat(factoid["createdAt"]).isNotNull()
        assertThat(factoid).doesNotContainKey("slug")

        // rpg's only tag is used alone: Uncharted, and last.
        val regions: List<Map<String, Any>> = JsonPath.read(body, "$.regions")
        assertThat(regions.last()["name"]).isEqualTo("Uncharted")
        assertThat(placeOf(body, "cobol ish")["region"]).isEqualTo(0)
    }

    @Test
    fun `counts match the public taxonomy, and unpublished, deleted, future and hidden posts aren't counted`() {
        val taxonomy = mockMvc.get("/taxonomy").andReturn().response.contentAsString
        val articles: Map<String, Int> = JsonPath.read(taxonomy, "$.tags")
        val factoids: Map<String, Int> = JsonPath.read(taxonomy, "$.factoidTags")
        val body = atlas()
        val places: List<Map<String, Any>> = JsonPath.read(body, "$.places")

        assertThat(places.map { it["tag"] })
            .containsExactlyInAnyOrderElementsOf(articles.keys + factoids.keys)
        places.forEach {
            assertThat(it["articleCount"])
                .describedAs("${it["tag"]}")
                .isEqualTo(articles[it["tag"]] ?: 0)
            assertThat(it["factoidCount"])
                .describedAs("${it["tag"]}")
                .isEqualTo(factoids[it["tag"]] ?: 0)
        }
        assertThat(places.map { it["tag"] }).doesNotContain("drafty", "gone", "later", "secret")
        assertThat(stored().keys).doesNotContain("drafty", "gone", "later", "secret")
    }

    @Test
    fun `the layout is computed once and stored, and later requests draw it from storage`() {
        val first = atlas()
        val laidOut = placedAt()
        assertThat(stored()).isEqualTo(positions(first))

        // A post that changes the ties but brings no new tag: nothing moves.
        post("four", "kotlin", "psychology", "auth")
        val again = atlas()

        assertThat(positions(again)).isEqualTo(positions(first))
        assertThat(placedAt()).isEqualTo(laidOut)
    }

    @Test
    fun `a post with a new tag places it beside its strongest relative, and nothing else moves`() {
        val before = atlas()
        val springRegion = placeOf(before, "spring")["region"]

        post("boot", "spring", "spring-boot")
        val after = atlas()

        assertThat(positions(after).filterKeys { it != "spring-boot" }).isEqualTo(positions(before))
        assertThat(placeOf(after, "spring-boot")["region"]).isEqualTo(springRegion)
        assertThat(stored().keys).contains("spring-boot")
    }

    @Test
    fun `a tag no longer used leaves the map but keeps its place for when it's back`() {
        atlas()
        val kotlin = stored().getValue("kotlin")
        val post = posts.getValue("kotlin")

        postRepository.save(post.copy(deleted = true))
        assertThat(positions(atlas())).doesNotContainKey("kotlin")
        assertThat(stored()).containsEntry("kotlin", kotlin)

        postRepository.save(post.copy(deleted = false))
        assertThat(positions(atlas())).containsEntry("kotlin", kotlin)
    }

    @Test
    fun `an unchanged map is answered 304 to a matching If-None-Match`() {
        val etag = mockMvc.get("/atlas").andReturn().response.getHeader("ETag")!!

        mockMvc
            .get("/atlas") { header("If-None-Match", etag) }
            .andExpect { status { isNotModified() } }
    }

    @Test
    fun `a place lists everything found there, and an unknown one is 404`() {
        mockMvc.get("/atlas/places/JAVA").andExpect {
            status { isOk() }
            jsonPath("$.place.tag") { value("java") }
            jsonPath("$.regionName") { isString() }
            jsonPath("$.articles.length()") { value(3) }
            jsonPath("$.articles[0].slug") { isString() }
            jsonPath("$.articles[0].tags") { isArray() }
            jsonPath("$.factoids[0].selector") { value("hotspot") }
            jsonPath("$.factoids[0].createdAt") { isString() }
        }
        mockMvc.get("/atlas/places/drafty").andExpect { status { isNotFound() } }
    }

    @Test
    fun `only an admin can lay the map out again`() {
        atlas()
        val laidOut = placedAt()

        mockMvc.post("/admin/atlas/relayout").andExpect { status { isUnauthorized() } }
        mockMvc
            .post("/admin/atlas/relayout") { header("Authorization", "Bearer ${token(Role.USER)}") }
            .andExpect { status { isForbidden() } }
        assertThat(placedAt()).isEqualTo(laidOut)

        Thread.sleep(5)
        mockMvc
            .post("/admin/atlas/relayout") {
                header("Authorization", "Bearer ${token(Role.ADMIN)}")
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.places[?(@.tag == 'java')].articleCount") { value(3) }
                jsonPath("$.regions") { isNotEmpty() }
            }
        assertThat(placedAt().values).allSatisfy { at ->
            assertThat(at).isAfter(laidOut.values.max())
        }
    }
}
