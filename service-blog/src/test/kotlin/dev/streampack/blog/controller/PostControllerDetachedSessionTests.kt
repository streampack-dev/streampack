/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.blog.entity.Category
import dev.streampack.blog.entity.Post
import dev.streampack.blog.entity.PostCategory
import dev.streampack.blog.entity.PostTag
import dev.streampack.blog.entity.Slug
import dev.streampack.blog.entity.Tag
import dev.streampack.blog.model.PostStatus
import dev.streampack.blog.repository.CategoryRepository
import dev.streampack.blog.repository.PostCategoryRepository
import dev.streampack.blog.repository.PostRepository
import dev.streampack.blog.repository.PostTagRepository
import dev.streampack.blog.repository.SlugRepository
import dev.streampack.blog.repository.TagRepository
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

/**
 * Exercises the public posts endpoint without a test method transaction. Transactional controller
 * tests can keep saved entities attached long enough to hide lazy proxy failures that appear in the
 * deployed request path.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PostControllerDetachedSessionTests {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var slugRepository: SlugRepository
    @Autowired lateinit var tagRepository: TagRepository
    @Autowired lateinit var postTagRepository: PostTagRepository
    @Autowired lateinit var categoryRepository: CategoryRepository
    @Autowired lateinit var postCategoryRepository: PostCategoryRepository
    @Autowired lateinit var entityManager: EntityManager

    private var slug: Slug? = null
    private var post: Post? = null
    private var author: User? = null
    private var tag: Tag? = null
    private var postTag: PostTag? = null
    private var category: Category? = null
    private var postCategory: PostCategory? = null

    @AfterEach
    fun tearDown() {
        postTag?.let { postTagRepository.deleteById(it.id) }
        postCategory?.let { postCategoryRepository.deleteById(it.id) }
        slug?.let { slugRepository.deleteById(it.id) }
        post?.let { postRepository.deleteById(it.id) }
        tag?.let { tagRepository.deleteById(it.id) }
        category?.let { categoryRepository.deleteById(it.id) }
        author?.let { userRepository.deleteById(it.id) }
    }

    @Test
    fun `GET posts resolves summary relations outside test transaction`() {
        val now = Instant.now()
        author =
            userRepository.saveAndFlush(
                User(
                    username = "detached-session-author",
                    email = "detached-session-author@test.com",
                    displayName = "Detached Session Author",
                    emailVerified = true,
                    role = Role.USER,
                )
            )
        post =
            postRepository.saveAndFlush(
                Post(
                    title = "Detached Session Post",
                    markdownSource = "Published content.",
                    renderedHtml = "<p>Published content.</p>",
                    excerpt = "Published content.",
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
                    path = "2026/04/detached-session-post",
                    post = post!!,
                    canonical = true,
                    createdAt = now,
                )
            )
        tag = tagRepository.saveAndFlush(Tag(name = "detached-tag", slug = "detached-tag"))
        postTag = postTagRepository.saveAndFlush(PostTag(post = post!!, tag = tag!!))
        category =
            categoryRepository.saveAndFlush(
                Category(name = "Detached Category", slug = "detached-category")
            )
        postCategory =
            postCategoryRepository.saveAndFlush(PostCategory(post = post!!, category = category!!))
        entityManager.clear()

        mockMvc.get("/posts?page=0&size=20").andExpect {
            status { isOk() }
            jsonPath("$.posts[?(@.title == 'Detached Session Post')].authorDisplayName") {
                value("Detached Session Author")
            }
            jsonPath("$.posts[?(@.title == 'Detached Session Post')].tags[0]") {
                value("detached-tag")
            }
            jsonPath("$.posts[?(@.title == 'Detached Session Post')].categories[0]") {
                value("Detached Category")
            }
        }
    }
}
