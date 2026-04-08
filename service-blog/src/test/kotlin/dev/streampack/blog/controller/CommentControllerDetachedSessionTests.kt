/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.blog.entity.Comment
import dev.streampack.blog.entity.Post
import dev.streampack.blog.entity.Slug
import dev.streampack.blog.model.PostStatus
import dev.streampack.blog.repository.CommentRepository
import dev.streampack.blog.repository.PostRepository
import dev.streampack.blog.repository.SlugRepository
import dev.streampack.core.entity.User
import dev.streampack.core.model.Role
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.JwtService
import jakarta.persistence.EntityManager
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put

/** Exercises comment creation without a test transaction masking lazy proxies. */
@SpringBootTest
@AutoConfigureMockMvc
class CommentControllerDetachedSessionTests {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var commentRepository: CommentRepository
    @Autowired lateinit var slugRepository: SlugRepository
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var entityManager: EntityManager

    private var slug: Slug? = null
    private var post: Post? = null
    private var comment: Comment? = null
    private var postAuthor: User? = null
    private var commenter: User? = null

    @AfterEach
    fun tearDown() {
        comment?.let { commentRepository.deleteById(it.id) }
        slug?.let { slugRepository.deleteById(it.id) }
        post?.let { postRepository.deleteById(it.id) }
        commenter?.let { userRepository.deleteById(it.id) }
        postAuthor?.let { userRepository.deleteById(it.id) }
    }

    @Test
    fun `POST comment notifies post author outside test transaction`() {
        val now = Instant.now()
        postAuthor =
            userRepository.saveAndFlush(
                User(
                    username = "detached-comment-post-author",
                    email = "detached-comment-post-author@test.com",
                    displayName = "Detached Comment Post Author",
                    emailVerified = true,
                    role = Role.USER,
                )
            )
        commenter =
            userRepository.saveAndFlush(
                User(
                    username = "detached-commenter",
                    email = "detached-commenter@test.com",
                    displayName = "Detached Commenter",
                    emailVerified = true,
                    role = Role.USER,
                )
            )
        val token = jwtService.generateToken(commenter!!.toUserPrincipal())
        post =
            postRepository.saveAndFlush(
                Post(
                    title = "Detached Comment Post",
                    markdownSource = "Post content.",
                    renderedHtml = "<p>Post content.</p>",
                    excerpt = "Post content.",
                    status = PostStatus.APPROVED,
                    publishedAt = now.minus(1, ChronoUnit.HOURS),
                    author = postAuthor,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        slug =
            slugRepository.saveAndFlush(
                Slug(
                    path = "2026/04/detached-comment-post",
                    post = post!!,
                    canonical = true,
                    createdAt = now,
                )
            )
        entityManager.clear()

        mockMvc
            .post("/posts/2026/04/detached-comment-post/comments") {
                contentType = MediaType.APPLICATION_JSON
                header("Authorization", "Bearer $token")
                content = """{"markdownSource":"This is my detached comment."}"""
            }
            .andExpect {
                status { isCreated() }
                jsonPath("$.authorDisplayName") { value("Detached Commenter") }
            }
    }

    @Test
    fun `PUT comment edit resolves author outside test transaction`() {
        val now = Instant.now()
        postAuthor =
            userRepository.saveAndFlush(
                User(
                    username = "detached-edit-comment-post-author",
                    email = "detached-edit-comment-post-author@test.com",
                    displayName = "Detached Edit Comment Post Author",
                    emailVerified = true,
                    role = Role.USER,
                )
            )
        commenter =
            userRepository.saveAndFlush(
                User(
                    username = "detached-edit-commenter",
                    email = "detached-edit-commenter@test.com",
                    displayName = "Detached Edit Commenter",
                    emailVerified = true,
                    role = Role.USER,
                )
            )
        val token = jwtService.generateToken(commenter!!.toUserPrincipal())
        post =
            postRepository.saveAndFlush(
                Post(
                    title = "Detached Edit Comment Post",
                    markdownSource = "Post content.",
                    renderedHtml = "<p>Post content.</p>",
                    excerpt = "Post content.",
                    status = PostStatus.APPROVED,
                    publishedAt = now.minus(1, ChronoUnit.HOURS),
                    author = postAuthor,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        comment =
            commentRepository.saveAndFlush(
                Comment(
                    post = post!!,
                    author = commenter!!,
                    markdownSource = "Original comment.",
                    renderedHtml = "<p>Original comment.</p>",
                    createdAt = now,
                    updatedAt = now,
                )
            )
        entityManager.clear()

        mockMvc
            .put("/comments/${comment!!.id}") {
                contentType = MediaType.APPLICATION_JSON
                header("Authorization", "Bearer $token")
                content = """{"markdownSource":"Updated detached comment."}"""
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.authorDisplayName") { value("Detached Edit Commenter") }
                jsonPath("$.renderedHtml") { value("<p>Updated detached comment.</p>") }
            }
    }
}
