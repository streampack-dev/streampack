/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.blog.entity.Category
import dev.streampack.blog.entity.Post
import dev.streampack.blog.entity.PostCategory
import dev.streampack.blog.entity.Slug
import dev.streampack.blog.model.PostStatus
import dev.streampack.blog.repository.CategoryRepository
import dev.streampack.blog.repository.PostCategoryRepository
import dev.streampack.blog.repository.PostRepository
import dev.streampack.blog.repository.SlugRepository
import dev.streampack.core.entity.User
import dev.streampack.core.model.Role
import dev.streampack.core.repository.UserRepository
import dev.streampack.test.ResetDatabaseBeforeEach
import dev.streampack.test.TestChannelConfiguration
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/**
 * A post in a `_` category is reachable at its address and nowhere else (#254), the feeds included:
 * a `_pages` page with its bare slug, and a `_test` post that kept the dated slug it was made with.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ResetDatabaseBeforeEach
@Import(TestChannelConfiguration::class)
class SystemPostFeedsTests {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var slugRepository: SlugRepository
    @Autowired lateinit var categoryRepository: CategoryRepository
    @Autowired lateinit var postCategoryRepository: PostCategoryRepository

    @BeforeEach
    fun setUp() {
        val author =
            userRepository.save(
                User(
                    username = "sysfeeds",
                    email = "sysfeeds@test.com",
                    displayName = "Sys Feeds",
                    emailVerified = true,
                    role = Role.ADMIN,
                ),
            )
        fun post(title: String, slug: String, category: String?) {
            val saved =
                postRepository.save(
                    Post(
                        title = title,
                        markdownSource = title,
                        renderedHtml = "<p>$title</p>",
                        excerpt = "About $title",
                        status = PostStatus.APPROVED,
                        publishedAt = Instant.now().minus(1, ChronoUnit.HOURS),
                        author = author,
                    ),
                )
            slugRepository.save(Slug(path = slug, post = saved, canonical = true))
            if (category != null) {
                val c =
                    categoryRepository.findByName(category)
                        ?: categoryRepository.save(Category(name = category, slug = category))
                postCategoryRepository.save(PostCategory(post = saved, category = c))
            }
        }
        post("An Ordinary Post", "2026/10/an-ordinary-post", null)
        post("A Test Post", "2026/10/a-test-post", "_test")
        post("The About Page", "about-us", "_pages")
    }

    @ParameterizedTest
    @ValueSource(strings = ["/blog/rss.xml", "/blog/atom.xml", "/feed.xml", "/feed.atom"])
    fun `system posts are in no feed, ordinary ones still are`(feed: String) {
        val body = mockMvc.get(feed).andReturn().response.contentAsString
        assertTrue(body.contains("An Ordinary Post"), body)
        assertFalse(body.contains("A Test Post"), body)
        assertFalse(body.contains("a-test-post"), body)
        assertFalse(body.contains("The About Page"), body)
        assertFalse(body.contains("about-us"), body)
    }
}
