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
import jakarta.persistence.EntityManager
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/** Exercises feed rendering without a test method transaction masking lazy proxies. */
@SpringBootTest
@AutoConfigureMockMvc
class BlogFeedDetachedSessionTests {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var slugRepository: SlugRepository
    @Autowired lateinit var entityManager: EntityManager

    private var slug: Slug? = null
    private var post: Post? = null
    private var author: User? = null

    @AfterEach
    fun tearDown() {
        slug?.let { slugRepository.deleteById(it.id) }
        post?.let { postRepository.deleteById(it.id) }
        author?.let { userRepository.deleteById(it.id) }
    }

    @Test
    fun `GET feed atom resolves author display name outside test transaction`() {
        val now = Instant.now()
        author =
            userRepository.saveAndFlush(
                User(
                    username = "detached-feed-author",
                    email = "detached-feed-author@test.com",
                    displayName = "Detached Feed Author",
                    emailVerified = true,
                    role = Role.USER,
                )
            )
        post =
            postRepository.saveAndFlush(
                Post(
                    title = "Detached Feed Post",
                    markdownSource = "Feed content",
                    renderedHtml = "<p>Feed content</p>",
                    excerpt = "A detached feed post",
                    status = PostStatus.APPROVED,
                    publishedAt = now.minus(1, ChronoUnit.HOURS),
                    author = author,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        slug =
            slugRepository.saveAndFlush(
                Slug(
                    path = "2026/04/detached-feed-post",
                    post = post!!,
                    canonical = true,
                    createdAt = now,
                )
            )
        entityManager.clear()

        mockMvc.get("/feed.atom").andExpect {
            status { isOk() }
            content { string(org.hamcrest.Matchers.containsString("Detached Feed Author")) }
        }
    }
}
