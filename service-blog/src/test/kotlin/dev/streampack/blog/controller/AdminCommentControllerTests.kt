/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.blog.entity.Comment
import dev.streampack.blog.entity.Post
import dev.streampack.blog.model.PostStatus
import dev.streampack.blog.repository.CommentRepository
import dev.streampack.blog.repository.PostRepository
import dev.streampack.core.entity.User
import dev.streampack.core.model.Role
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.JwtService
import dev.streampack.test.ResetDatabaseBeforeEach
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete

/** Integration tests for admin comment deletion endpoints */
@SpringBootTest
@AutoConfigureMockMvc
@ResetDatabaseBeforeEach
class AdminCommentControllerTests {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var commentRepository: CommentRepository
    @Autowired lateinit var jwtService: JwtService

    private lateinit var adminUser: User
    private lateinit var adminToken: String
    private lateinit var regularUser: User
    private lateinit var regularUserToken: String
    private lateinit var publishedPost: Post

    @BeforeEach
    fun setUp() {
        val now = Instant.now()

        adminUser =
            userRepository.save(
                User(
                    username = "commentadmin",
                    email = "commentadmin@test.com",
                    displayName = "Comment Admin",
                    emailVerified = true,
                    role = Role.ADMIN,
                )
            )
        adminToken = jwtService.generateToken(adminUser.toUserPrincipal())

        regularUser =
            userRepository.save(
                User(
                    username = "regularcommenter",
                    email = "regularcommenter@test.com",
                    displayName = "Regular Commenter",
                    emailVerified = true,
                    role = Role.USER,
                )
            )
        regularUserToken = jwtService.generateToken(regularUser.toUserPrincipal())

        publishedPost =
            postRepository.save(
                Post(
                    title = "Admin Test Post",
                    markdownSource = "Content.",
                    renderedHtml = "<p>Content.</p>",
                    excerpt = "Content.",
                    status = PostStatus.APPROVED,
                    publishedAt = now.minus(1, ChronoUnit.HOURS),
                    author = regularUser,
                    createdAt = now,
                    updatedAt = now,
                )
            )
    }

    private fun createComment(): Comment {
        return commentRepository.save(
            Comment(
                post = publishedPost,
                author = regularUser,
                markdownSource = "A comment to delete.",
                renderedHtml = "<p>A comment to delete.</p>",
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        )
    }

    @Test
    fun `DELETE soft-deletes comment by default`() {
        val comment = createComment()

        mockMvc
            .delete("/admin/comments/${comment.id}") {
                contentType = MediaType.APPLICATION_JSON
                header("Authorization", "Bearer $adminToken")
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.id") { value(comment.id.toString()) }
                jsonPath("$.message") { value("Comment deleted") }
            }

        val updated = commentRepository.findById(comment.id).orElse(null)
        assertTrue(updated.deleted)
    }

    @Test
    fun `DELETE with hard=true permanently removes comment`() {
        val comment = createComment()
        val commentId = comment.id

        mockMvc
            .delete("/admin/comments/$commentId?hard=true") {
                contentType = MediaType.APPLICATION_JSON
                header("Authorization", "Bearer $adminToken")
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.id") { value(commentId.toString()) }
                jsonPath("$.message") { value("Comment permanently removed") }
            }

        assertFalse(commentRepository.existsById(commentId))
    }

    @Test
    fun `DELETE by non-admin returns 403`() {
        val comment = createComment()

        mockMvc
            .delete("/admin/comments/${comment.id}") {
                contentType = MediaType.APPLICATION_JSON
                header("Authorization", "Bearer $regularUserToken")
            }
            .andExpect {
                status { isForbidden() }
                jsonPath("$.detail") { value("Insufficient privileges: requires ADMIN") }
            }
    }

    @Test
    fun `DELETE unauthenticated returns 401`() {
        val comment = createComment()

        mockMvc
            .delete("/admin/comments/${comment.id}") { contentType = MediaType.APPLICATION_JSON }
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.detail") { value("Authentication required") }
            }
    }

    @Test
    fun `DELETE nonexistent comment returns 404`() {
        mockMvc
            .delete("/admin/comments/00000000-0000-0000-0000-000000000001") {
                contentType = MediaType.APPLICATION_JSON
                header("Authorization", "Bearer $adminToken")
            }
            .andExpect {
                status { isNotFound() }
                jsonPath("$.detail") { value("Comment not found") }
            }
    }
}
