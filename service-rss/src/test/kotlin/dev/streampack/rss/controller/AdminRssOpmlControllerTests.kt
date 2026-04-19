/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.controller

import dev.streampack.core.entity.User
import dev.streampack.core.model.Role
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.JwtService
import dev.streampack.rss.entity.RssFeed
import dev.streampack.rss.repository.RssFeedRepository
import dev.streampack.test.ResetDatabaseBeforeEach
import dev.streampack.web.auth.AuthCookieNames
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
@ResetDatabaseBeforeEach
class AdminRssOpmlControllerTests {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var feedRepository: RssFeedRepository

    private lateinit var adminToken: String
    private lateinit var userToken: String

    @BeforeEach
    fun setUp() {
        val admin =
            userRepository.save(
                User(
                    username = "rssadmin",
                    email = "rssadmin@test.com",
                    displayName = "RSS Admin",
                    emailVerified = true,
                    role = Role.ADMIN,
                )
            )
        adminToken = jwtService.generateToken(admin.toUserPrincipal())

        val user =
            userRepository.save(
                User(
                    username = "rssuser",
                    email = "rssuser@test.com",
                    displayName = "RSS User",
                    emailVerified = true,
                    role = Role.USER,
                )
            )
        userToken = jwtService.generateToken(user.toUserPrincipal())
    }

    @Test
    fun `GET admin rss opml requires authentication`() {
        mockMvc.get("/admin/rss/opml").andExpect {
            status { isUnauthorized() }
            jsonPath("$.detail") { value("Authentication required") }
        }
    }

    @Test
    fun `GET admin rss opml requires admin role`() {
        mockMvc
            .get("/admin/rss/opml") { header("Authorization", "Bearer $userToken") }
            .andExpect {
                status { isForbidden() }
                jsonPath("$.detail") { value("Insufficient privileges: requires ADMIN") }
            }
    }

    @Test
    fun `GET admin rss opml returns xml export for admin`() {
        feedRepository.save(
            RssFeed(
                feedUrl = "https://example.com/feed.xml",
                siteUrl = "https://example.com",
                title = "Example Feed",
                active = true,
            )
        )

        mockMvc
            .get("/admin/rss/opml") { header("Authorization", "Bearer $adminToken") }
            .andExpect {
                status { isOk() }
                content { contentTypeCompatibleWith(MediaType.APPLICATION_XML) }
                content {
                    string(org.hamcrest.Matchers.containsString("https://example.com/feed.xml"))
                }
            }
    }

    @Test
    fun `POST admin rss opml import returns summary for admin`() {
        mockMvc
            .post("/admin/rss/opml/import") {
                header("Authorization", "Bearer $adminToken")
                contentType = MediaType.TEXT_PLAIN
                content = "not a valid opml document"
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.ignored") { exists() }
            }
    }

    @Test
    fun `POST admin rss opml import accepts xml content type`() {
        mockMvc
            .post("/admin/rss/opml/import") {
                header("Authorization", "Bearer $adminToken")
                contentType = MediaType.APPLICATION_XML
                content = """<opml version="2.0"><head><title>Empty</title></head><body /></opml>"""
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.added") { value(0) }
            }
    }

    @Test
    fun `POST admin rss opml import accepts access token cookie`() {
        mockMvc
            .post("/admin/rss/opml/import") {
                cookie(Cookie(AuthCookieNames.ACCESS_TOKEN, adminToken))
                contentType = MediaType.APPLICATION_XML
                content = """<opml version="2.0"><head><title>Empty</title></head><body /></opml>"""
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.added") { value(0) }
            }
    }

    @Test
    fun `POST admin rss opml import accepts multipart file upload`() {
        val file =
            MockMultipartFile(
                "file",
                "feeds.opml",
                MediaType.APPLICATION_XML_VALUE,
                """
                <opml version="2.0">
                  <body />
                </opml>
                """
                    .trimIndent()
                    .toByteArray(),
            )

        mockMvc
            .perform(
                multipart("/admin/rss/opml/import")
                    .file(file)
                    .header("Authorization", "Bearer $adminToken")
            )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.added").value(0))
    }
}
