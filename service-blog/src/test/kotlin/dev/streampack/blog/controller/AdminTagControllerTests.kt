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

    private fun count(table: String): Int =
        jdbc.queryForObject("SELECT COUNT(*) FROM $table", Int::class.java)!!

    /** Everything an alias or split could touch, to show a preview touched none of it. */
    private fun snapshot(): Map<String, Any?> =
        mapOf(
            "post_tags" to
                jdbc.queryForList(
                    "SELECT post_id::text, tag_id::text FROM post_tags ORDER BY 1, 2"
                ),
            "tags" to jdbc.queryForList("SELECT id::text, name, slug FROM tags ORDER BY name"),
            "factoid tags" to
                jdbc.queryForList(
                    "SELECT id::text, attribute_value, updated_at FROM factoid_attributes " +
                        "WHERE attribute_type = 'TAGS' ORDER BY 1"
                ),
            "tag_review" to
                jdbc.queryForList("SELECT tag, status, acted_by FROM tag_review ORDER BY tag"),
            "tag_alias" to jdbc.queryForList("SELECT alias FROM tag_alias ORDER BY alias"),
            "tag_action" to count("tag_action"),
        )

    private fun changeOf(result: org.springframework.test.web.servlet.ResultActionsDsl) =
        result
            .andExpect { status { isOk() } }
            .andReturn()
            .response
            .contentAsString
            .let {
                listOf(
                    JsonPath.read<String>(it, "$.tag"),
                    JsonPath.read<List<String>>(it, "$.now"),
                    JsonPath.read<Int>(it, "$.posts"),
                    JsonPath.read<Int>(it, "$.factoids"),
                )
            }

    @Test
    fun `the new endpoints and dry runs answer 401 to no one and 403 to a user`() {
        post("one", "compiler")
        val id = reviewId("compiler")
        mockMvc.get("/admin/tags/review/$id").andExpect { status { isUnauthorized() } }
        mockMvc
            .get("/admin/tags/review/$id") { header("Authorization", "Bearer $userToken") }
            .andExpect { status { isForbidden() } }
        for ((path, body) in
            listOf(
                "/admin/tags/review/$id/alias?dryRun=true" to """{"tag":"compilers"}""",
                "/admin/tags/review/$id/split?dryRun=true" to """{"parts":["java","kotlin"]}""",
                "/admin/tags/aliases?dryRun=true" to """{"alias":"compiler","tag":"compilers"}""",
            )) {
            mockMvc
                .post(path) {
                    contentType = MediaType.APPLICATION_JSON
                    content = body
                }
                .andExpect { status { isUnauthorized() } }
            mockMvc
                .post(path) {
                    header("Authorization", "Bearer $userToken")
                    contentType = MediaType.APPLICATION_JSON
                    content = body
                }
                .andExpect { status { isForbidden() } }
        }
        assertThat(reviews.findByTag("compiler")!!.status).isEqualTo(TagReviewStatus.OPEN)
    }

    @Test
    fun `one review entry is found by id, and an unknown one is a 404`() {
        post("one", "compiler")
        mockMvc
            .get("/admin/tags/review/${reviewId("compiler")}") {
                header("Authorization", "Bearer $adminToken")
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.id") { value(reviewId("compiler")) }
                jsonPath("$.tag") { value("compiler") }
                jsonPath("$.hintKind") { value("PLURAL") }
                jsonPath("$.hintTags[0]") { value("compilers") }
                jsonPath("$.source") { value("post") }
                jsonPath("$.status") { value("OPEN") }
            }
        mockMvc
            .get("/admin/tags/review/00000000-0000-0000-0000-000000000000") {
                header("Authorization", "Bearer $adminToken")
            }
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `every status filter carries its own total and page count`() {
        // Five entries: open x2, kept, dismissed, aliased.
        post("one", "compiler", "kotlins", "javas", "java kotlin", "kubernete")
        listOf("javas" to "keep", "java kotlin" to "dismiss").forEach { (tag, action) ->
            mockMvc
                .post("/admin/tags/review/${reviewId(tag)}/$action") {
                    header("Authorization", "Bearer $adminToken")
                }
                .andExpect { status { isOk() } }
        }
        mockMvc
            .post("/admin/tags/review/${reviewId("kubernete")}/alias") {
                header("Authorization", "Bearer $adminToken")
                contentType = MediaType.APPLICATION_JSON
                content = """{"tag":"kubernetes"}"""
            }
            .andExpect { status { isOk() } }
        for ((status, total, pages) in
            listOf(
                Triple("open", 2, 1),
                Triple("kept", 1, 1),
                Triple("dismissed", 1, 1),
                Triple("aliased", 1, 1),
                Triple("split", 0, 0),
                Triple("all", 5, 3),
            )) {
            mockMvc
                .get("/admin/tags/review?status=$status&size=2") {
                    header("Authorization", "Bearer $adminToken")
                }
                .andExpect {
                    status { isOk() }
                    jsonPath("$.totalCount") { value(total) }
                    jsonPath("$.totalPages") { value(pages) }
                    jsonPath("$.openCount") { value(2) }
                    jsonPath("$.entries.length()") { value(minOf(total, 2)) }
                }
        }
        mockMvc
            .get("/admin/tags/review?status=all&size=2&page=2") {
                header("Authorization", "Bearer $adminToken")
            }
            .andExpect {
                jsonPath("$.totalCount") { value(5) }
                jsonPath("$.entries.length()") { value(1) }
            }
    }

    @Test
    fun `a review alias dry run counts what the alias then does, and changes nothing`() {
        val one = post("one", "compiler", "java")
        post("two", "compilers", "compiler")
        say("gcc=a compiler")
        say("gcc.tags=compiler, java")
        val id = reviewId("compiler")
        val before = snapshot()

        val preview =
            changeOf(
                mockMvc.post("/admin/tags/review/$id/alias?dryRun=true") {
                    header("Authorization", "Bearer $adminToken")
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"tag":"compilers"}"""
                }
            )
        assertThat(preview).containsExactly("compiler", listOf("compilers"), 2, 1)
        assertThat(snapshot()).isEqualTo(before)
        assertThat(tagsOfPost(one)).containsExactly("compiler", "java")
        assertThat(factoidTags("gcc")).isEqualTo("compiler,java")

        // An older client's body, with "alias": null, is still taken.
        val real =
            changeOf(
                mockMvc.post("/admin/tags/review/$id/alias") {
                    header("Authorization", "Bearer $adminToken")
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"tag":"compilers","alias":null}"""
                }
            )
        assertThat(real).isEqualTo(preview)
        assertThat(tagsOfPost(one)).containsExactly("compilers", "java")
    }

    @Test
    fun `a dry run is refused as the action would be`() {
        post("one", "compiler")
        val before = snapshot()
        mockMvc
            .post("/admin/tags/review/${reviewId("compiler")}/alias?dryRun=true") {
                header("Authorization", "Bearer $adminToken")
                contentType = MediaType.APPLICATION_JSON
                content = """{"tag":"no such tag"}"""
            }
            .andExpect { status { isBadRequest() } }
        mockMvc
            .post("/admin/tags/review/00000000-0000-0000-0000-000000000000/split?dryRun=true") {
                header("Authorization", "Bearer $adminToken")
            }
            .andExpect { status { isNotFound() } }
        assertThat(snapshot()).isEqualTo(before)
    }

    @Test
    fun `a review split dry run counts what the split then does, and changes nothing`() {
        val one = post("one", "java kotlin")
        post("two", "java kotlin", "java")
        say("jk=both")
        say("jk.tags=java kotlin, compilers")
        val id = reviewId("java kotlin")
        val before = snapshot()

        val preview =
            changeOf(
                mockMvc.post("/admin/tags/review/$id/split?dryRun=true") {
                    header("Authorization", "Bearer $adminToken")
                }
            )
        assertThat(preview).containsExactly("java kotlin", listOf("java", "kotlin"), 2, 1)
        assertThat(snapshot()).isEqualTo(before)
        assertThat(tagsOfPost(one)).containsExactly("java kotlin")

        val real =
            changeOf(
                mockMvc.post("/admin/tags/review/$id/split") {
                    header("Authorization", "Bearer $adminToken")
                }
            )
        assertThat(real).isEqualTo(preview)
        assertThat(tagsOfPost(one)).containsExactly("java", "kotlin")
    }

    @Test
    fun `an alias dry run counts what making the alias then does, and changes nothing`() {
        val one = post("one", "k8s")
        post("two", "k8s", "kubernetes")
        say("kube=x")
        say("kube.tags=K8s")
        val before = snapshot()

        val preview =
            changeOf(
                mockMvc.post("/admin/tags/aliases?dryRun=true") {
                    header("Authorization", "Bearer $adminToken")
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"alias":"K8s","tag":"kubernetes"}"""
                }
            )
        assertThat(preview).containsExactly("k8s", listOf("kubernetes"), 2, 1)
        assertThat(snapshot()).isEqualTo(before)
        assertThat(tagsOfPost(one)).containsExactly("k8s")

        val real =
            changeOf(
                mockMvc.post("/admin/tags/aliases") {
                    header("Authorization", "Bearer $adminToken")
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"alias":"K8s","tag":"kubernetes"}"""
                }
            )
        assertThat(real).isEqualTo(preview)
        assertThat(tagsOfPost(one)).containsExactly("kubernetes")
        assertThat(factoidTags("kube")).isEqualTo("kubernetes")
    }
}
