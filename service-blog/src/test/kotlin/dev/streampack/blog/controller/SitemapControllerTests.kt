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
import dev.streampack.test.ResetDatabaseBeforeEach
import dev.streampack.test.TestChannelConfiguration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/** Integration tests for sitemap.xml generation */
@SpringBootTest
@AutoConfigureMockMvc
@ResetDatabaseBeforeEach
@Import(TestChannelConfiguration::class)
class SitemapControllerTests {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var slugRepository: SlugRepository

    private lateinit var slugPath: String

    @BeforeEach
    fun setUp() {
        val now = Instant.now()
        val author =
            userRepository.save(
                User(
                    username = "sitemapauthor",
                    email = "sitemapauthor@test.com",
                    displayName = "Sitemap Author",
                    emailVerified = true,
                    role = Role.ADMIN,
                )
            )

        val post =
            postRepository.save(
                Post(
                    title = "Sitemap Post",
                    markdownSource = "Content",
                    renderedHtml = "<p>Content</p>",
                    excerpt = "A post for sitemap",
                    status = PostStatus.APPROVED,
                    publishedAt = now.minus(1, ChronoUnit.HOURS),
                    author = author,
                )
            )

        val publishedAt = post.publishedAt!!
        val dateTime = publishedAt.atZone(ZoneOffset.UTC)
        slugPath = "${dateTime.year}/${"%02d".format(dateTime.monthValue)}/sitemap-post"
        slugRepository.save(Slug(path = slugPath, post = post, canonical = true))

        // Draft post should NOT appear in sitemap
        postRepository.save(
            Post(
                title = "Draft Post",
                markdownSource = "Draft",
                renderedHtml = "<p>Draft</p>",
                status = PostStatus.DRAFT,
                author = author,
            )
        )
    }

    @Test
    fun `sitemap returns valid XML with published posts`() {
        mockMvc.get("/sitemap.xml").andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_XML) }
            content {
                string(
                    org.hamcrest.Matchers.containsString(
                        "http://www.sitemaps.org/schemas/sitemap/0.9"
                    )
                )
            }
            content { string(org.hamcrest.Matchers.containsString(slugPath)) }
            content { string(org.hamcrest.Matchers.containsString("<priority>1.0</priority>")) }
            content { string(org.hamcrest.Matchers.containsString("<priority>0.8</priority>")) }
        }
    }

    @Test
    fun `sitemap excludes draft posts`() {
        mockMvc.get("/sitemap.xml").andExpect {
            status { isOk() }
            content {
                string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Draft")))
            }
        }
    }

    @Test
    fun `sitemap includes home page entry`() {
        mockMvc.get("/sitemap.xml").andExpect {
            status { isOk() }
            content {
                string(org.hamcrest.Matchers.containsString("<changefreq>daily</changefreq>"))
            }
        }
    }
}
