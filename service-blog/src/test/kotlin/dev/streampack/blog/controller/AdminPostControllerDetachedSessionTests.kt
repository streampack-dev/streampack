/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.blog.entity.Post
import dev.streampack.blog.model.PostStatus
import dev.streampack.blog.repository.PostRepository
import dev.streampack.core.entity.User
import dev.streampack.core.model.Role
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.JwtService
import jakarta.persistence.EntityManager
import java.time.Instant
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.put

/** Exercises admin post edits without a test transaction masking lazy proxies. */
@SpringBootTest
@AutoConfigureMockMvc
class AdminPostControllerDetachedSessionTests {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var entityManager: EntityManager

    private var post: Post? = null
    private var postAuthor: User? = null
    private var admin: User? = null

    @AfterEach
    fun tearDown() {
        post?.let { postRepository.deleteById(it.id) }
        postAuthor?.let { userRepository.deleteById(it.id) }
        admin?.let { userRepository.deleteById(it.id) }
    }

    @Test
    fun `PUT admin post edit resolves author outside test transaction`() {
        val now = Instant.now()
        postAuthor =
            userRepository.saveAndFlush(
                User(
                    username = "detached-edit-post-author",
                    email = "detached-edit-post-author@test.com",
                    displayName = "Detached Edit Post Author",
                    emailVerified = true,
                    role = Role.USER,
                )
            )
        admin =
            userRepository.saveAndFlush(
                User(
                    username = "detached-edit-admin",
                    email = "detached-edit-admin@test.com",
                    displayName = "Detached Edit Admin",
                    emailVerified = true,
                    role = Role.ADMIN,
                )
            )
        val token = jwtService.generateToken(admin!!.toUserPrincipal())
        post =
            postRepository.saveAndFlush(
                Post(
                    title = "Detached Edit Original",
                    markdownSource = "Original content.",
                    renderedHtml = "<p>Original content.</p>",
                    excerpt = "Original content.",
                    status = PostStatus.DRAFT,
                    author = postAuthor,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        entityManager.clear()

        mockMvc
            .put("/admin/posts/${post!!.id}") {
                contentType = MediaType.APPLICATION_JSON
                header("Authorization", "Bearer $token")
                content =
                    """{"title":"Detached Edit Updated","markdownSource":"Updated content."}"""
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.title") { value("Detached Edit Updated") }
                jsonPath("$.authorDisplayName") { value("Detached Edit Post Author") }
            }
    }

    @Test
    fun `PUT admin post approval resolves author outside test transaction`() {
        val now = Instant.now()
        postAuthor =
            userRepository.saveAndFlush(
                User(
                    username = "detached-approve-post-author",
                    email = "detached-approve-post-author@test.com",
                    displayName = "Detached Approve Post Author",
                    emailVerified = true,
                    role = Role.USER,
                )
            )
        admin =
            userRepository.saveAndFlush(
                User(
                    username = "detached-approve-admin",
                    email = "detached-approve-admin@test.com",
                    displayName = "Detached Approve Admin",
                    emailVerified = true,
                    role = Role.ADMIN,
                )
            )
        val token = jwtService.generateToken(admin!!.toUserPrincipal())
        post =
            postRepository.saveAndFlush(
                Post(
                    title = "Detached Approve Draft",
                    markdownSource = "Draft content.",
                    renderedHtml = "<p>Draft content.</p>",
                    excerpt = "Draft content.",
                    status = PostStatus.DRAFT,
                    author = postAuthor,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        entityManager.clear()

        mockMvc
            .put("/admin/posts/${post!!.id}/approve") {
                contentType = MediaType.APPLICATION_JSON
                header("Authorization", "Bearer $token")
                content = """{"publishedAt":"$now"}"""
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.title") { value("Detached Approve Draft") }
                jsonPath("$.authorDisplayName") { value("Detached Approve Post Author") }
            }
    }
}
