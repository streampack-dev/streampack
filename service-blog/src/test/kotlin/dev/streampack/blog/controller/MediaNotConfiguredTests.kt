/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.blog.entity.Post
import dev.streampack.blog.entity.PostVersion
import dev.streampack.blog.entity.Slug
import dev.streampack.blog.model.PostStatus
import dev.streampack.blog.repository.PostRepository
import dev.streampack.blog.repository.PostVersionRepository
import dev.streampack.blog.repository.SlugRepository
import dev.streampack.core.entity.User
import dev.streampack.core.model.Role
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.JwtService
import dev.streampack.test.AudioFixtures
import dev.streampack.test.ResetDatabaseBeforeEach
import dev.streampack.test.TestChannelConfiguration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart

/**
 * With no media directory (the default), media is off and inert (#196): uploads answer 503 saying
 * so, `/media/…` is 404, and posts and feeds list no versions, even one left in the database from
 * when media was on.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ResetDatabaseBeforeEach
@Import(TestChannelConfiguration::class)
class MediaNotConfiguredTests {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var slugRepository: SlugRepository
    @Autowired lateinit var versionRepository: PostVersionRepository
    @Autowired lateinit var jwtService: JwtService

    private lateinit var adminToken: String
    private lateinit var post: Post

    @BeforeEach
    fun setUp() {
        val admin =
            userRepository.save(
                User(
                    username = "offadmin",
                    email = "offadmin@test.com",
                    displayName = "Off Admin",
                    emailVerified = true,
                    role = Role.ADMIN,
                )
            )
        adminToken = jwtService.generateToken(admin.toUserPrincipal())
        val publishedAt = Instant.now().minus(1, ChronoUnit.HOURS)
        post =
            postRepository.save(
                Post(
                    title = "Quiet Post",
                    markdownSource = "Text.",
                    renderedHtml = "<p>Text.</p>",
                    status = PostStatus.APPROVED,
                    publishedAt = publishedAt,
                    author = admin,
                )
            )
        val at = publishedAt.atZone(ZoneOffset.UTC)
        slugRepository.save(
            Slug(
                path = "${at.year}/${"%02d".format(at.monthValue)}/quiet-post",
                post = post,
                canonical = true,
            )
        )
        versionRepository.save(
            PostVersion(
                post = post,
                mediaType = "audio/mpeg",
                size = 100,
                sourceHash = "a".repeat(64),
                contentHash = "b".repeat(64),
                storageKey = "ab/0190a1b2-0000-7000-8000-000000000000",
            )
        )
    }

    @Test
    fun `uploads say media isn't configured`() {
        mockMvc
            .multipart("/admin/posts/${post.id}/versions/audio") {
                file(MockMultipartFile("file", "a.mp3", "audio/mpeg", AudioFixtures.mp3()))
                header("Authorization", "Bearer $adminToken")
            }
            .andExpect {
                status { isServiceUnavailable() }
                jsonPath("$.detail") { value(containsString("STREAMPACK_MEDIA_DIR")) }
            }
    }

    @Test
    fun `nothing is served or listed`() {
        mockMvc.get("/media/posts/${post.id}/audio").andExpect { status { isNotFound() } }
        mockMvc.get("/posts/${post.id}").andExpect {
            status { isOk() }
            jsonPath("$.versions.length()") { value(0) }
        }
        mockMvc.get("/posts").andExpect {
            status { isOk() }
            jsonPath("$.posts[0].title") { value("Quiet Post") }
            jsonPath("$.posts[0].versionKinds.length()") { value(0) }
        }
        for (path in listOf("/feed.xml", "/feed.atom", "/blog/rss.xml", "/blog/atom.xml")) {
            mockMvc.get(path).andExpect {
                status { isOk() }
                content { string(containsString("Quiet Post")) }
                content { string(org.hamcrest.Matchers.not(containsString("enclosure"))) }
            }
        }
        val podcast = FeedXml(mockMvc.get("/podcast.xml").andReturn().response.contentAsString)
        assertEquals(0, podcast.count("/rss/channel/item"))
        // Defaults: the site's name, no author, artwork, category or owner, not explicit.
        assertEquals("Nevet", podcast.text("/rss/channel/title"))
        assertEquals("false", podcast.text("/rss/channel/itunes:explicit"))
        for (tag in listOf("author", "image", "category", "owner")) {
            assertEquals(0, podcast.count("/rss/channel/itunes:$tag"), tag)
        }
    }
}
