/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.controller

import dev.streampack.core.entity.User
import dev.streampack.core.model.Role
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.JwtService
import dev.streampack.rss.entity.RssEntry
import dev.streampack.rss.entity.RssFeed
import dev.streampack.rss.repository.RssEntryRepository
import dev.streampack.rss.repository.RssFeedRepository
import dev.streampack.rss.service.FeedTagSamples.parse
import dev.streampack.rss.service.FeedTagSamples.rss
import dev.streampack.rss.service.FeedTagService
import dev.streampack.taxonomy.TagVocabulary
import dev.streampack.taxonomy.entity.Tag
import dev.streampack.taxonomy.repository.TagRepository
import dev.streampack.test.ResetDatabaseBeforeEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/** The feed-tag admin endpoints (#139), and the mapped tags on `/rss/items`. */
@SpringBootTest
@AutoConfigureMockMvc
@ResetDatabaseBeforeEach
class AdminRssTagControllerTests {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var feeds: RssFeedRepository
    @Autowired lateinit var entries: RssEntryRepository
    @Autowired lateinit var feedTags: FeedTagService
    @Autowired lateinit var tags: TagRepository
    @Autowired lateinit var vocabulary: TagVocabulary

    private lateinit var adminToken: String
    private lateinit var userToken: String

    private fun token(name: String, role: Role): String =
        jwtService.generateToken(
            userRepository
                .save(
                    User(
                        username = name,
                        email = "$name@test.com",
                        displayName = name,
                        emailVerified = true,
                        role = role,
                    )
                )
                .toUserPrincipal()
        )

    @BeforeEach
    fun setUp() {
        adminToken = token("tagadmin", Role.ADMIN)
        userToken = token("taguser", Role.USER)
        listOf("java", "go").forEach { tags.save(Tag(name = it, slug = vocabulary.uniqueSlug(it))) }
        val feed =
            feeds.save(
                RssFeed(
                    feedUrl = "https://a.example/feed.xml",
                    siteUrl = "https://a.example",
                    title = "A",
                )
            )
        val synd =
            parse(
                rss(
                    "https://a.example",
                    mapOf("1" to listOf("Java", "Golang", "Rumour", "Helidon")),
                )
            )
        val entry =
            entries.save(
                RssEntry(
                    feed = feed,
                    guid = "https://a.example/1",
                    link = "https://a.example/1",
                    title = "One",
                )
            )
        feedTags.record(listOf(entry to synd.entries.single()))
    }

    private fun postJson(path: String, body: String, token: String = adminToken) =
        mockMvc.post(path) {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

    @Test
    fun `listing feed tags is for admins`() {
        mockMvc.get("/admin/rss/tags").andExpect { status { isUnauthorized() } }
        mockMvc
            .get("/admin/rss/tags") { header("Authorization", "Bearer $userToken") }
            .andExpect { status { isForbidden() } }
        postJson("/admin/rss/tags/ignore", """{"name":"rumour"}""", userToken).andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `the waiting feed tags, with counts and examples`() {
        mockMvc
            .get("/admin/rss/tags") { header("Authorization", "Bearer $adminToken") }
            .andExpect {
                status { isOk() }
                jsonPath("$.waitingCount") { value(3) }
                jsonPath("$.promoteEntries") { value(3) }
                jsonPath("$.promoteFeeds") { value(2) }
                jsonPath("$.tags.length()") { value(3) }
                jsonPath("$.tags[?(@.name == 'golang')].entries") { value(1) }
                jsonPath("$.tags[?(@.name == 'golang')].feeds") { value(1) }
                jsonPath("$.tags[?(@.name == 'golang')].written[0]") { value("Golang") }
                jsonPath("$.tags[?(@.name == 'golang')].examples[0].title") { value("One") }
                jsonPath("$.tags[?(@.name == 'golang')].examples[0].feedTitle") { value("A") }
            }
        mockMvc
            .get("/admin/rss/tags?status=bogus") { header("Authorization", "Bearer $adminToken") }
            .andExpect { status { isBadRequest() } }
    }

    @Test
    fun `an admin maps, ignores and creates, and items carry the mapped tags`() {
        mockMvc.get("/rss/items").andExpect {
            status { isOk() }
            jsonPath("$.items[0].categories.length()") { value(4) }
            jsonPath("$.items[0].tags.length()") { value(1) }
            jsonPath("$.items[0].tags[0]") { value("java") }
        }
        postJson("/admin/rss/tags/map", """{"name":"Golang","tag":"go"}""").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("MAPPED") }
            jsonPath("$.tag") { value("go") }
            jsonPath("$.decidedBy") { value("tagadmin") }
        }
        postJson("/admin/rss/tags/ignore", """{"name":"rumour"}""").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("IGNORED") }
        }
        postJson("/admin/rss/tags/create", """{"name":"helidon"}""").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("CREATED") }
            jsonPath("$.tag") { value("helidon") }
        }
        mockMvc.get("/rss/items").andExpect {
            jsonPath("$.items[0].tags.length()") { value(3) }
            jsonPath("$.items[0].tags[0]") { value("go") }
            jsonPath("$.items[0].tags[1]") { value("helidon") }
            jsonPath("$.items[0].tags[2]") { value("java") }
        }
        mockMvc
            .get("/admin/rss/tags") { header("Authorization", "Bearer $adminToken") }
            .andExpect { jsonPath("$.waitingCount") { value(0) } }
    }

    @Test
    fun `bad decisions are refused`() {
        postJson("/admin/rss/tags/map", """{"name":"golang","tag":"not a tag"}""").andExpect {
            status { isBadRequest() }
        }
        postJson("/admin/rss/tags/create", """{"name":"never seen"}""").andExpect {
            status { isNotFound() }
        }
    }
}
