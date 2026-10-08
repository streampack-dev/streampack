/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import db.migration.V72__tag_cleanup
import dev.streampack.blog.entity.Post
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
import dev.streampack.taxonomy.TagVocabulary
import dev.streampack.test.ResetDatabaseBeforeEach
import dev.streampack.test.TestChannelConfiguration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.messaging.support.MessageBuilder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/**
 * The #140 tag cleanup (V72) against a database seeded as production is: posts and factoids
 * carrying every old tag on the list, posts that already carry the kept tag, `auth` beside
 * `oauth2`, and Atlas places for old and kept names alike. Flyway has already run V72 on the empty
 * test database (where it does nothing); the tests run its logic again on the seeded one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ResetDatabaseBeforeEach
@Import(TestChannelConfiguration::class)
class TagCleanupMigrationTests {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var postTagRepository: PostTagRepository
    @Autowired lateinit var slugRepository: SlugRepository
    @Autowired lateinit var vocabulary: TagVocabulary
    @Autowired lateinit var dataSource: DataSource
    @Autowired lateinit var jdbc: JdbcTemplate

    private lateinit var author: User
    private val posts = mutableMapOf<String, Post>()

    /** Keepers: tags the list keeps as they are, on a post and a factoid. */
    private val keepers =
        listOf(
            "cicd",
            "devops",
            "load testing",
            "performance",
            "bruce",
            "toon",
            "funny",
            "turtles",
            "web development",
            "spring boot",
        )

    private fun tag(name: String): UUID =
        jdbc
            .queryForList("SELECT id FROM tags WHERE name = ?", UUID::class.java, name)
            .firstOrNull()
            ?: UUID.randomUUID().also {
                jdbc.update(
                    "INSERT INTO tags (id, name, slug, deleted) VALUES (?, ?, ?, FALSE)",
                    it,
                    name,
                    vocabulary.uniqueSlug(name),
                )
            }

    /** A published post carrying [tags] exactly as named (stored as they were before PR 1). */
    private fun post(key: String, vararg tags: String): Post {
        val post =
            postRepository.save(
                Post(
                    title = "Post $key",
                    markdownSource = "Body",
                    renderedHtml = "<p>Body</p>",
                    status = PostStatus.APPROVED,
                    publishedAt = Instant.now().minusSeconds(60),
                    author = author,
                )
            )
        slugRepository.save(Slug(path = "2026/10/$key", post = post, canonical = true))
        tags.forEach {
            jdbc.update(
                "INSERT INTO post_tags (id, post_id, tag_id) VALUES (?, ?, ?)",
                UUID.randomUUID(),
                post.id,
                tag(it),
            )
        }
        posts[key] = post
        return post
    }

    /** A factoid whose stored tag list is [csv], as written. */
    private fun factoid(selector: String, csv: String) {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO factoids (id, selector) VALUES (?, ?)", id, selector)
        jdbc.update(
            "INSERT INTO factoid_attributes (id, factoid_id, attribute_type, attribute_value) VALUES (?, ?, 'TEXT', ?)",
            UUID.randomUUID(),
            id,
            "$selector is a thing",
        )
        jdbc.update(
            "INSERT INTO factoid_attributes (id, factoid_id, attribute_type, attribute_value) VALUES (?, ?, 'TAGS', ?)",
            UUID.randomUUID(),
            id,
            csv,
        )
    }

    private fun place(tag: String, x: Double, y: Double) {
        jdbc.update(
            "INSERT INTO atlas_place (tag, region, x, y, placed_at) VALUES (?, 1, ?, ?, now())",
            tag,
            x,
            y,
        )
    }

    private fun factoidTags(selector: String): String =
        jdbc.queryForObject(
            """
            SELECT fa.attribute_value FROM factoid_attributes fa JOIN factoids f ON f.id = fa.factoid_id
            WHERE f.selector = ? AND fa.attribute_type = 'TAGS'
            """,
            String::class.java,
            selector,
        )!!

    private fun tagsOf(key: String): List<String> =
        postTagRepository.findNamesByPost(posts.getValue(key).id).sorted()

    private fun placeAt(tag: String): Pair<Double, Double>? =
        jdbc
            .query(
                "SELECT x, y FROM atlas_place WHERE tag = ?",
                { rs, _ ->
                    rs.getDouble(1) to rs.getDouble(2)
                },
                tag,
            )
            .firstOrNull()

    private fun names(sql: String): List<String> =
        jdbc.queryForList(sql, String::class.java).filterNotNull()

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

    private fun cleanup(): List<String> = dataSource.connection.use { V72__tag_cleanup.cleanup(it) }

    /** Everything the cleanup may touch, to compare a second run against. */
    private fun snapshot(): List<Any> =
        listOf(
            names(
                "SELECT p.title || ':' || t.name FROM post_tags pt JOIN posts p ON p.id = pt.post_id JOIN tags t ON t.id = pt.tag_id ORDER BY 1"
            ),
            names(
                "SELECT f.selector || '=' || fa.attribute_value || '@' || fa.updated_at FROM factoid_attributes fa JOIN factoids f ON f.id = fa.factoid_id ORDER BY 1"
            ),
            names("SELECT id || name || slug FROM tags ORDER BY 1"),
            names("SELECT alias || tag_id FROM tag_alias ORDER BY 1"),
            names("SELECT term FROM tag_stop ORDER BY 1"),
            names("SELECT tag || x || y FROM atlas_place ORDER BY 1"),
            names("SELECT count(*)::text FROM tag_action"),
        )

    @BeforeEach
    fun seed() {
        author =
            userRepository.save(
                User(
                    username = "cleanup-author",
                    email = "author@cleanup.test",
                    displayName = "Author",
                    emailVerified = true,
                    role = Role.USER,
                )
            )
        // Posts: every old tag on a post, some beside their kept tag, and the keepers.
        post("p1", "content-extraction", "jakarta-ee", "anti-pattern", "java")
        post("p2", "build tools", "build tool")
        post("p3", "compilers", "frameworks", "tools", "scm", "authorization", "authentication")
        post("p4", "jakarta", "jakarta-ee")
        post("p5", "rdms", "consoleio", "console", "26", "java")
        post(
            "p6",
            "framework",
            "tooling",
            "tunnel",
            "distributed computing",
            "compiler",
            "version control",
            "specification",
            "security",
            "identity",
        )
        post("p7", "formal-languages")
        post("p8", *keepers.toTypedArray())
        // Old tags with a row but no posts, as factoid tags written through the vocabulary have.
        tag("remote-access")
        tag("auth")
        tag("self-hosted")

        // Factoids, as stored before PR 1: hyphens, spaces and case as written.
        factoid("loadgen", "load-testing,performance")
        factoid("extract", "content-extraction")
        factoid("jee", "Jakarta-EE, java")
        factoid("ap", "anti-pattern")
        factoid("ssh", "remote-access,tunneling,self-hosted")
        factoid("grammar", "formal-languages,compilers")
        factoid("authorization", "security,auth,access-control")
        factoid("oauth2", "oauth2,auth,security")
        factoid("oidc", "auth,identity,security")
        factoid("authentication", "auth,identity")
        factoid("kotauth", "auth,self-hosted,kotlin")
        factoid("headscale", "self-hosted, vpn")
        factoid("savant", "new,ai")
        factoid("make", "build tool,build tools,tools")
        factoid("rdb", "rdms,database,rdbms")
        factoid("io", "consoleio,console")
        factoid("spring", "ioc,di,spring")
        factoid("cluster", "distributed,distributed computing")
        factoid("rfc", "standard,specification")
        factoid("keep", keepers.joinToString(","))

        jdbc.update(
            "INSERT INTO atlas_region (id, name, x, y) VALUES (1, 'r', 0, 0) ON CONFLICT DO NOTHING"
        )
        place("load-testing", 1.0, 1.0) // its kept tag has no place: moves
        place("compilers", 2.0, 2.0) // its kept tag has one: dropped
        place("compiler", 3.0, 3.0)
        place("jakarta-ee", 4.0, 4.0) // renamed first, so jakarta ee takes this one
        place("jakarta", 5.0, 5.0)
        place("self-hosted", 6.0, 6.0)
        place("auth", 7.0, 7.0)
        place("java", 8.0, 8.0)
    }

    @Test
    fun `the cleanup applies the list, and a second run changes nothing`() {
        val keeperIds = keepers.associateWith { tag(it) }
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger(V72__tag_cleanup::class.java) as Logger
        val level = logger.level
        logger.level = ch.qos.logback.classic.Level.INFO
        logger.addAppender(appender)
        val changes =
            try {
                cleanup()
            } finally {
                logger.detachAppender(appender)
                logger.level = level
            }

        // It logged as it went: a start, a line per entry, and a summary.
        val logged = appender.list.map { it.formattedMessage }
        assertThat(logged.first()).isEqualTo("V72: start")
        assertThat(logged)
            .anyMatch { it.startsWith("V72: load-testing -> load testing: ") }
            .anyMatch { it.startsWith("V72: self-hosted removed: ") }
            .anyMatch { it.startsWith("V72: auth removed: ") }
        assertThat(logged.last())
            .matches("V72: done in \\d+ ms: .* posts, .* factoids, .* tags, .* atlas rows")
        assertThat(changes)
            .hasSize(V72__tag_cleanup.RENAMES.size + V72__tag_cleanup.MERGES.size + 4)

        // Renames and merges moved the posts, one row per tag per post.
        assertThat(tagsOf("p1"))
            .containsExactly("anti pattern", "content extraction", "jakarta ee", "java")
        assertThat(tagsOf("p2")).containsExactly("build tool")
        assertThat(tagsOf("p3"))
            .containsExactly(
                "compiler",
                "framework",
                "identity",
                "security",
                "tooling",
                "version control",
            )
        assertThat(tagsOf("p4")).containsExactly("jakarta ee")
        assertThat(tagsOf("p5")).containsExactly("console", "java", "rdbms")
        assertThat(tagsOf("p7")).containsExactly("formal languages")
        assertThat(
                names(
                    "SELECT post_id || '/' || tag_id FROM post_tags GROUP BY post_id, tag_id HAVING count(*) > 1"
                )
            )
            .isEmpty()

        // ...and the factoids, in place and without repeats.
        assertThat(factoidTags("loadgen")).isEqualTo("load testing,performance")
        assertThat(factoidTags("extract")).isEqualTo("content extraction")
        assertThat(factoidTags("jee")).isEqualTo("jakarta ee,java")
        assertThat(factoidTags("ap")).isEqualTo("anti pattern")
        assertThat(factoidTags("ssh")).isEqualTo("remote access,tunnel")
        assertThat(factoidTags("grammar")).isEqualTo("formal languages,compiler")
        assertThat(factoidTags("make")).isEqualTo("build tool,tooling")
        assertThat(factoidTags("rdb")).isEqualTo("rdbms,database")
        assertThat(factoidTags("io")).isEqualTo("console")
        assertThat(factoidTags("spring")).isEqualTo("dependency injection,spring")
        assertThat(factoidTags("cluster")).isEqualTo("distributed computing")
        assertThat(factoidTags("rfc")).isEqualTo("specification")

        // No old name has a row; every kept one does.
        val oldNames =
            (V72__tag_cleanup.RENAMES + V72__tag_cleanup.MERGES).map { it.first } +
                V72__tag_cleanup.REMOVALS +
                "auth"
        assertThat(names("SELECT name FROM tags")).doesNotContainAnyElementsOf(oldNames)
        assertThat(names("SELECT name FROM tags"))
            .containsAll((V72__tag_cleanup.RENAMES + V72__tag_cleanup.MERGES).map { it.second })
        // A rename with no kept row renames the row in place.
        assertThat(names("SELECT slug FROM tags WHERE name = 'formal languages'"))
            .containsExactly("formal-languages")
        // A merge into a tag with no row makes one, with its own slug.
        assertThat(names("SELECT slug FROM tags WHERE name = 'rdbms'")).containsExactly("rdbms")

        // Merges leave aliases; renames need none (the old name normalizes to the kept one).
        val aliases =
            jdbc
                .queryForList(
                    "SELECT a.alias, t.name FROM tag_alias a JOIN tags t ON t.id = a.tag_id"
                )
                .associate { it["alias"] as String to it["name"] as String }
        assertThat(aliases).isEqualTo(V72__tag_cleanup.MERGES.toMap())
        assertThat(names("SELECT DISTINCT created_by FROM tag_alias")).containsExactly("migration")

        // Lookups by an old name find the moved posts, through PR 2's resolve.
        mockMvc.get("/posts?tag=compilers").andExpect { jsonPath("$.totalCount") { value(2) } }
        mockMvc.get("/posts?tag=jakarta").andExpect { jsonPath("$.totalCount") { value(2) } }
        mockMvc.get("/posts?tag=jakarta-ee").andExpect { jsonPath("$.totalCount") { value(2) } }
        mockMvc.get("/posts?tag=rdms").andExpect { jsonPath("$.totalCount") { value(1) } }
        mockMvc.get("/posts?tag=formal-languages").andExpect {
            jsonPath("$.totalCount") { value(1) }
        }
        val search = say("tag compilers") as OperationResult.Success
        assertThat(search.payload.toString()).contains("{{ref:grammar}}")

        // The removals are gone, and stoplisted in the form resolve compares.
        assertThat(factoidTags("ssh")).doesNotContain("self")
        assertThat(factoidTags("headscale")).isEqualTo("vpn")
        assertThat(factoidTags("savant")).isEqualTo("ai")
        assertThat(names("SELECT term FROM tag_stop ORDER BY term"))
            .containsExactly("26", "new", "self hosted")
        assertThat(vocabulary.resolve("self-hosted")!!.name).isNull()
        assertThat(vocabulary.resolve("26")!!.name).isNull()

        // auth is gone, kotauth has identity, oauth2 and authorization keep the rest.
        assertThat(factoidTags("kotauth")).isEqualTo("identity,kotlin")
        assertThat(factoidTags("oauth2")).isEqualTo("oauth2,security")
        assertThat(factoidTags("authorization")).isEqualTo("security,access control")
        assertThat(factoidTags("oidc")).isEqualTo("identity,security")
        assertThat(factoidTags("authentication")).isEqualTo("identity")
        assertThat(aliases).doesNotContainKey("auth")
        assertThat(names("SELECT term FROM tag_stop")).doesNotContain("auth")
        assertThat(vocabulary.resolve("auth")!!.name).isEqualTo("auth")

        // The Atlas: moved where the kept tag had no place, dropped where it had.
        assertThat(placeAt("load testing")).isEqualTo(1.0 to 1.0)
        assertThat(placeAt("load-testing")).isNull()
        assertThat(placeAt("compiler")).isEqualTo(3.0 to 3.0)
        assertThat(placeAt("compilers")).isNull()
        assertThat(placeAt("jakarta ee")).isEqualTo(4.0 to 4.0)
        assertThat(placeAt("jakarta")).isNull()
        assertThat(placeAt("self-hosted")).isNull()
        assertThat(placeAt("auth")).isNull()
        assertThat(placeAt("java")).isEqualTo(8.0 to 8.0)

        // The keepers are untouched.
        assertThat(tagsOf("p8")).containsExactlyInAnyOrderElementsOf(keepers)
        assertThat(factoidTags("keep")).isEqualTo(keepers.joinToString(","))
        keeperIds.forEach { (name, id) -> assertThat(tag(name)).isEqualTo(id) }

        // Search finds a post by its new tag name, and the old one is out of its vector.
        mockMvc.get("/posts/search?q=rdbms").andExpect {
            jsonPath("$.posts[*].title") { value(org.hamcrest.Matchers.contains("Post p5")) }
        }
        fun vector(title: String) =
            names("SELECT search_vector::text FROM posts WHERE title = '$title'").single()
        assertThat(vector("Post p5")).contains("'rdbm").doesNotContain("rdms")
        // A rename in place: the tags trigger refreshed it.
        assertThat(vector("Post p7")).contains("'languag'").doesNotContain("'formal-languag'")

        // The history shows it, as done by the migration.
        val actions =
            jdbc.queryForList("SELECT action, subject, actor FROM tag_action").map {
                "${it["action"]} ${it["subject"]} ${it["actor"]}"
            }
        assertThat(actions)
            .contains(
                "ALIAS load-testing migration",
                "ALIAS di migration",
                "REMOVE self-hosted migration",
                "STOP self hosted migration",
                "REMOVE auth migration",
            )
            .hasSize(V72__tag_cleanup.RENAMES.size + V72__tag_cleanup.MERGES.size + 3 * 2 + 1)

        // A second run is harmless.
        val before = snapshot()
        assertThat(cleanup()).isEmpty()
        assertThat(snapshot()).isEqualTo(before)
    }

    @Test
    fun `a database without the listed tags is left alone`() {
        jdbc.update("TRUNCATE post_tags, tags, factoid_attributes, factoids, atlas_place CASCADE")
        post("solo", "java")
        factoid("plain", "kotlin,oauth2")
        place("java", 1.0, 1.0)
        assertThat(cleanup()).isEmpty()
        assertThat(names("SELECT term FROM tag_stop")).isEmpty()
        assertThat(names("SELECT action FROM tag_action")).isEmpty()
        assertThat(tagsOf("solo")).containsExactly("java")
        assertThat(factoidTags("plain")).isEqualTo("kotlin,oauth2")
        assertThat(names("SELECT name FROM tags")).containsExactly("java")
        assertThat(names("SELECT alias FROM tag_alias")).isEmpty()
    }
}
