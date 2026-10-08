/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import com.jayway.jsonpath.JsonPath
import dev.streampack.blog.entity.Post
import dev.streampack.blog.entity.PostTag
import dev.streampack.blog.entity.Slug
import dev.streampack.blog.model.PostStatus
import dev.streampack.blog.repository.PostRepository
import dev.streampack.blog.repository.PostTagRepository
import dev.streampack.blog.repository.SlugRepository
import dev.streampack.core.entity.User
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.JwtService
import dev.streampack.factoid.service.FactoidService
import dev.streampack.taxonomy.TagCuration
import dev.streampack.taxonomy.TagUsages
import dev.streampack.taxonomy.TagVocabulary
import dev.streampack.taxonomy.model.TagReviewStatus
import dev.streampack.taxonomy.repository.TagRepository
import dev.streampack.taxonomy.repository.TagReviewRepository
import dev.streampack.test.ResetDatabaseBeforeEach
import dev.streampack.test.TestChannelConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.messaging.support.MessageBuilder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * The tag vocabulary end to end (#140): writes stored canonical, an alias finding its tag's posts,
 * factoids and place, and the admin queue re-pointing posts and factoids in one transaction.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ResetDatabaseBeforeEach
@Import(TestChannelConfiguration::class, AdminTagControllerTests.Exploding::class)
class AdminTagControllerTests {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var postTagRepository: PostTagRepository
    @Autowired lateinit var slugRepository: SlugRepository
    @Autowired lateinit var tagRepository: TagRepository
    @Autowired lateinit var reviews: TagReviewRepository
    @Autowired lateinit var vocabulary: TagVocabulary
    @Autowired lateinit var curation: TagCuration
    @Autowired lateinit var factoidService: FactoidService
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var jdbc: JdbcTemplate

    private lateinit var author: User
    private lateinit var adminToken: String
    private lateinit var userToken: String

    /** A usage that writes, then fails, when "boom" is re-pointed: the action must roll back. */
    @TestConfiguration
    class Exploding {
        @Bean
        fun explodingUsages(jdbc: JdbcTemplate): TagUsages =
            object : TagUsages {
                override val kind = "test"

                override fun retag(from: String, to: List<String>, actor: String): Int {
                    if (from != "boom") return 0
                    jdbc.update(
                        "INSERT INTO tag_stop (term, created_by, created_at) VALUES ('evidence', 'x', now())"
                    )
                    error("boom")
                }
            }
    }

    private fun user(role: Role): User =
        userRepository.save(
            User(
                username = "tags-${role.name.lowercase()}",
                email = "${role.name.lowercase()}@tags.test",
                displayName = role.name,
                emailVerified = true,
                role = role,
            )
        )

    private fun post(slug: String, vararg tags: String): Post {
        val post =
            postRepository.save(
                Post(
                    title = "Post $slug",
                    markdownSource = "Body",
                    renderedHtml = "<p>Body</p>",
                    status = PostStatus.APPROVED,
                    publishedAt = java.time.Instant.now().minusSeconds(60),
                    author = author,
                )
            )
        slugRepository.save(Slug(path = "2026/10/$slug", post = post, canonical = true))
        vocabulary.acceptTags(tags.toList(), "post").forEach {
            postTagRepository.save(PostTag(post = post, tag = it))
        }
        return post
    }

    private fun tagsOfPost(post: Post): List<String> = postTagRepository.findNamesByPost(post.id)

    private fun say(text: String): Any? =
        eventGateway.process(
            MessageBuilder.withPayload(text)
                .setHeader(
                    Provenance.HEADER,
                    Provenance(protocol = Protocol.CONSOLE, serviceId = "test", replyTo = "local"),
                )
                .setHeader("nick", "tester")
                .build()
        )

    private fun factoidTags(selector: String): String? =
        factoidService
            .findBySelector(selector)
            .single { it.attributeType.name == "TAGS" }
            .attributeValue

    /** POST /posts as the author, with a form loaded a minute ago; the tags it answers with. */
    private var lastBody = ""

    private fun createPost(title: String, vararg tags: String): List<String> {
        val body =
            mockMvc
                .post("/posts") {
                    header("Authorization", "Bearer $userToken")
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        """{"title":"$title","markdownSource":"Body","tags":[""" +
                            tags.joinToString(",") { "\"$it\"" } +
                            """],"formLoadedAt":${System.currentTimeMillis() - 60_000}}"""
                }
                .andReturn()
                .response
                .contentAsString
        lastBody = body
        return JsonPath.read(body, "$.tags")
    }

    private fun reviewId(tag: String): String = reviews.findByTag(tag)!!.id.toString()

    @BeforeEach
    fun setUp() {
        author = user(Role.USER)
        adminToken = jwtService.generateToken(user(Role.ADMIN).toUserPrincipal())
        userToken = jwtService.generateToken(author.toUserPrincipal())
        listOf("compilers", "java", "kotlin", "kubernetes").forEach { vocabulary.ensureTag(it) }
    }

    @Test
    fun `the admin endpoints answer 401 to no one and 403 to a user`() {
        mockMvc.get("/admin/tags/review").andExpect { status { isUnauthorized() } }
        mockMvc
            .get("/admin/tags/review") { header("Authorization", "Bearer $userToken") }
            .andExpect { status { isForbidden() } }
        mockMvc
            .post("/admin/tags/aliases") {
                header("Authorization", "Bearer $userToken")
                contentType = MediaType.APPLICATION_JSON
                content = """{"alias":"k8s","tag":"kubernetes"}"""
            }
            .andExpect { status { isForbidden() } }
        mockMvc
            .delete("/admin/tags/stoplist?term=x") { header("Authorization", "Bearer $userToken") }
            .andExpect { status { isForbidden() } }
    }

    @Test
    fun `a doubtful new tag is queued with its hint, and listed`() {
        post("one", "compiler", "java")
        mockMvc
            .get("/admin/tags/review") { header("Authorization", "Bearer $adminToken") }
            .andExpect {
                status { isOk() }
                jsonPath("$.openCount") { value(1) }
                jsonPath("$.entries[0].tag") { value("compiler") }
                jsonPath("$.entries[0].hintKind") { value("PLURAL") }
                jsonPath("$.entries[0].hintTags[0]") { value("compilers") }
                jsonPath("$.entries[0].source") { value("post") }
                jsonPath("$.entries[0].status") { value("OPEN") }
            }
    }

    @Test
    fun `aliasing a queued tag re-points its posts and factoids, and the alias finds them`() {
        val one = post("one", "compiler", "java")
        val two = post("two", "compilers", "compiler")
        say("gcc=a compiler")
        say("gcc.tags=compiler, java")
        say("clang=another")
        say("clang.tags=compilers,compiler")

        mockMvc
            .post("/admin/tags/review/${reviewId("compiler")}/alias") {
                header("Authorization", "Bearer $adminToken")
                contentType = MediaType.APPLICATION_JSON
                content = """{"tag":"compilers"}"""
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.tag") { value("compiler") }
                jsonPath("$.now[0]") { value("compilers") }
                jsonPath("$.posts") { value(2) }
                jsonPath("$.factoids") { value(2) }
            }

        assertThat(tagsOfPost(one)).containsExactly("compilers", "java")
        assertThat(tagsOfPost(two)).containsExactly("compilers")
        assertThat(factoidTags("gcc")).isEqualTo("compilers,java")
        assertThat(factoidTags("clang")).isEqualTo("compilers")
        assertThat(tagRepository.findByName("compiler")).isNull()
        val entry = reviews.findByTag("compiler")!!
        assertThat(entry.status).isEqualTo(TagReviewStatus.ALIASED)
        assertThat(entry.actedBy).isEqualTo("tags-admin")

        // Lookups by the alias find the tag's posts, factoids and place.
        mockMvc.get("/posts?tag=Compiler").andExpect {
            status { isOk() }
            jsonPath("$.totalCount") { value(2) }
        }
        val search = say("tag compiler") as OperationResult.Success
        assertThat(search.payload.toString()).contains("{{ref:gcc}}", "{{ref:clang}}")
        mockMvc.get("/atlas/places/compiler").andExpect {
            status { isOk() }
            jsonPath("$.place.tag") { value("compilers") }
        }

        // And writing the alias stores the tag.
        assertThat(createPost("Three", "Compiler", "#Java"))
            .withFailMessage { lastBody }
            .containsExactly("compilers", "java")
    }

    @Test
    fun `splitting a missing-comma tag re-points its posts and factoids to the parts`() {
        val one = post("one", "java kotlin")
        val two = post("two", "java kotlin", "java")
        say("jk=both")
        say("jk.tags=java kotlin, compilers")

        mockMvc
            .post("/admin/tags/review/${reviewId("java kotlin")}/split") {
                header("Authorization", "Bearer $adminToken")
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.now.length()") { value(2) }
                jsonPath("$.posts") { value(2) }
                jsonPath("$.factoids") { value(1) }
            }

        assertThat(tagsOfPost(one)).containsExactly("java", "kotlin")
        assertThat(tagsOfPost(two)).containsExactly("java", "kotlin")
        assertThat(factoidTags("jk")).isEqualTo("java,kotlin,compilers")
        assertThat(reviews.findByTag("java kotlin")!!.status).isEqualTo(TagReviewStatus.SPLIT)
    }

    @Test
    fun `keep and dismiss close the entry and leave the tag`() {
        post("one", "compiler", "kotlins")
        mockMvc
            .post("/admin/tags/review/${reviewId("compiler")}/keep") {
                header("Authorization", "Bearer $adminToken")
            }
            .andExpect { status { isOk() } }
        mockMvc
            .post("/admin/tags/review/${reviewId("kotlins")}/dismiss") {
                header("Authorization", "Bearer $adminToken")
            }
            .andExpect { status { isOk() } }
        mockMvc
            .post("/admin/tags/review/${reviewId("kotlins")}/dismiss") {
                header("Authorization", "Bearer $adminToken")
            }
            .andExpect { status { isBadRequest() } }
        mockMvc
            .post("/admin/tags/review/00000000-0000-0000-0000-000000000000/keep") {
                header("Authorization", "Bearer $adminToken")
            }
            .andExpect { status { isNotFound() } }
        assertThat(tagRepository.findByName("compiler")).isNotNull()
        assertThat(reviews.findByTag("compiler")!!.status).isEqualTo(TagReviewStatus.KEPT)
    }

    @Test
    fun `an action that fails part-way changes nothing`() {
        val one = post("one", "boom", "java")
        say("bang=x")
        say("bang.tags=boom")
        assertThrows(IllegalStateException::class.java) { curation.alias("boom", "java", "admin") }

        assertThat(tagsOfPost(one)).containsExactly("boom", "java")
        assertThat(factoidTags("bang")).isEqualTo("boom")
        assertThat(tagRepository.findByName("boom")).isNotNull()
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tag_stop", Int::class.java)).isZero()
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tag_alias", Int::class.java)).isZero()
    }

    @Test
    fun `aliases can be created, listed and removed, and a new alias re-points what carries it`() {
        val one = post("one", "k8s")
        mockMvc
            .post("/admin/tags/aliases") {
                header("Authorization", "Bearer $adminToken")
                contentType = MediaType.APPLICATION_JSON
                content = """{"alias":"K8s","tag":"kubernetes"}"""
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.posts") { value(1) }
            }
        assertThat(tagsOfPost(one)).containsExactly("kubernetes")
        mockMvc
            .get("/admin/tags/aliases") { header("Authorization", "Bearer $adminToken") }
            .andExpect {
                status { isOk() }
                jsonPath("$[0].alias") { value("k8s") }
                jsonPath("$[0].tag") { value("kubernetes") }
                jsonPath("$[0].createdBy") { value("tags-admin") }
            }
        mockMvc
            .post("/admin/tags/aliases") {
                header("Authorization", "Bearer $adminToken")
                contentType = MediaType.APPLICATION_JSON
                content = """{"alias":"helm","tag":"no such tag"}"""
            }
            .andExpect { status { isBadRequest() } }
        mockMvc
            .delete("/admin/tags/aliases?alias=k8s") {
                header("Authorization", "Bearer $adminToken")
            }
            .andExpect { status { isOk() } }
        mockMvc
            .delete("/admin/tags/aliases?alias=k8s") {
                header("Authorization", "Bearer $adminToken")
            }
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `a stoplisted term is dropped from posts written after it`() {
        mockMvc
            .post("/admin/tags/stoplist") {
                header("Authorization", "Bearer $adminToken")
                contentType = MediaType.APPLICATION_JSON
                content = """{"term":"Self-Hosted"}"""
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.term") { value("self hosted") }
            }
        assertThat(createPost("VPN", "self-hosted", "java"))
            .withFailMessage { lastBody }
            .containsExactly("java")
        mockMvc
            .get("/admin/tags/stoplist") { header("Authorization", "Bearer $adminToken") }
            .andExpect { jsonPath("$[0].term") { value("self hosted") } }
        mockMvc
            .delete("/admin/tags/stoplist?term=self hosted") {
                header("Authorization", "Bearer $adminToken")
            }
            .andExpect { status { isOk() } }
        val actions =
            mockMvc
                .get("/admin/tags/actions") { header("Authorization", "Bearer $adminToken") }
                .andReturn()
                .response
                .contentAsString
        assertThat(JsonPath.read<List<String>>(actions, "$[*].action"))
            .containsExactly("UNSTOP", "STOP")
        assertThat(JsonPath.read<List<String>>(actions, "$[*].actor")).containsOnly("tags-admin")
    }
}
