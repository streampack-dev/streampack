/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.blog.entity.Post
import dev.streampack.blog.entity.Slug
import dev.streampack.blog.model.PostStatus
import dev.streampack.blog.repository.PostRepository
import dev.streampack.blog.repository.SlugRepository
import dev.streampack.core.entity.User
import dev.streampack.core.model.Role
import dev.streampack.core.repository.UserRepository
import dev.streampack.test.TestChannelConfiguration
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.transaction.annotation.Transactional

/** Integration tests for RSS/Atom feed endpoints */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(TestChannelConfiguration::class)
class RssFeedControllerTests {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var slugRepository: SlugRepository
    @Autowired lateinit var userRepository: UserRepository

    private lateinit var author: User

    @BeforeEach
    fun setUp() {
        author =
            userRepository.save(
                User(
                    username = "rssauthor",
                    email = "rssauthor@test.com",
                    displayName = "RSS Author",
                    emailVerified = true,
                    role = Role.USER,
                )
            )

        val now = Instant.now()
        val post =
            postRepository.save(
                Post(
                    title = "RSS Test Post",
                    markdownSource = "# RSS Test",
                    renderedHtml = "<h1>RSS Test</h1>",
                    excerpt = "RSS test excerpt",
                    status = PostStatus.APPROVED,
                    publishedAt = now.minus(1, ChronoUnit.HOURS),
                    author = author,
                )
            )
        slugRepository.save(Slug(path = "2026/02/rss-test-post", post = post, canonical = true))
    }

    @Test
    fun `GET rss_xml returns 200 with feed content type`() {
        mockMvc.get("/blog/rss.xml").andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith("application/rss+xml") }
            content { string(org.hamcrest.Matchers.containsString("<rss")) }
        }
    }

    @Test
    fun `GET atom_xml returns 200 with atom content type`() {
        mockMvc.get("/blog/atom.xml").andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith("application/atom+xml") }
            content { string(org.hamcrest.Matchers.containsString("<feed")) }
        }
    }

    @Test
    fun `GET rss_xml returns valid XML with feed metadata`() {
        val result =
            mockMvc
                .get("/blog/rss.xml")
                .andExpect { status { isOk() } }
                .andReturn()
                .response
                .contentAsString

        assertTrue(result.contains("<feed") || result.contains("<rss"))
        assertTrue(result.contains("<title>bytecode.news</title>"))
        assertTrue(
            result.contains(
                "<description>JVM ecosystem news and community content</description>"
            ) || result.contains("<subtitle>JVM ecosystem news and community content</subtitle>")
        )
        assertTrue(
            result.contains("<language>en-us</language>") || result.contains("xml:lang=\"en-us\"")
        )
    }

    @Test
    fun `GET rss_xml includes published post as entry`() {
        val result =
            mockMvc
                .get("/blog/rss.xml")
                .andExpect { status { isOk() } }
                .andReturn()
                .response
                .contentAsString

        assertTrue(result.contains("<item>") || result.contains("<entry>"))
        assertTrue(result.contains("<title>RSS Test Post</title>"))
        assertTrue(result.contains("2026/02/rss-test-post"))
        assertTrue(result.contains("RSS test excerpt"))
    }

    @Test
    fun `GET rss_xml requires no authentication`() {
        mockMvc.get("/blog/rss.xml").andExpect { status { isOk() } }
    }

    @Test
    fun `GET rss_xml with no posts returns empty feed container`() {
        slugRepository.deleteAll()
        postRepository.deleteAll()

        val result =
            mockMvc
                .get("/blog/rss.xml")
                .andExpect { status { isOk() } }
                .andReturn()
                .response
                .contentAsString

        assertTrue(result.contains("<channel>") || result.contains("<feed"))
        assertTrue(result.contains("<title>bytecode.news</title>"))
    }
}
