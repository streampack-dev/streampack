/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.blog.entity.Post
import dev.streampack.blog.model.PostStatus
import dev.streampack.blog.repository.PostRepository
import dev.streampack.core.entity.User
import dev.streampack.core.model.Role
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.JwtService
import dev.streampack.test.ResetDatabaseBeforeEach
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.*

/** Integration tests for admin post management endpoints */
@SpringBootTest
@AutoConfigureMockMvc
@ResetDatabaseBeforeEach
class AdminPostControllerTests {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var jwtService: JwtService

    private lateinit var adminUser: User
    private lateinit var adminToken: String
    private lateinit var regularUser: User
    private lateinit var regularUserToken: String
    private lateinit var draftPost: Post

    @BeforeEach
    fun setUp() {
        val now = Instant.now()

        adminUser =
            userRepository.save(
                User(
                    username = "postadmin",
                    email = "postadmin@test.com",
                    displayName = "Post Admin",
                    emailVerified = true,
                    role = Role.ADMIN,
                )
            )
        adminToken = jwtService.generateToken(adminUser.toUserPrincipal())

        regularUser =
            userRepository.save(
                User(
                    username = "postregular",
                    email = "postregular@test.com",
                    displayName = "Regular User",
                    emailVerified = true,
                    role = Role.USER,
                )
            )
        regularUserToken = jwtService.generateToken(regularUser.toUserPrincipal())

        draftPost =
            postRepository.save(
                Post(
                    title = "Draft for Admin",
                    markdownSource = "Draft content.",
                    renderedHtml = "<p>Draft content.</p>",
                    excerpt = "Draft content.",
                    status = PostStatus.DRAFT,
                    author = regularUser,
                    createdAt = now,
                    updatedAt = now,
                )
            )
    }

    // --- GET /admin/posts/pending ---

    @Test
    fun `GET pending returns draft list for admin`() {
        mockMvc
            .get("/admin/posts/pending") { header("Authorization", "Bearer $adminToken") }
            .andExpect {
                status { isOk() }
                jsonPath("$.posts") { isArray() }
                jsonPath("$.page") { value(0) }
            }
    }

    @Test
    fun `GET pending with deleted true returns soft-deleted drafts for admin`() {
        postRepository.save(
            Post(
                title = "Deleted Draft",
                markdownSource = "Deleted draft content.",
                renderedHtml = "<p>Deleted draft content.</p>",
                excerpt = "Deleted draft content.",
                status = PostStatus.DRAFT,
                deleted = true,
                author = regularUser,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        )

        mockMvc
            .get("/admin/posts/pending?deleted=true") {
                header("Authorization", "Bearer $adminToken")
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.posts") { isArray() }
                jsonPath("$.totalCount") { value(1) }
                jsonPath("$.posts[0].title") { value("Deleted Draft") }
            }
    }

    @Test
    fun `GET pending by non-admin returns 403`() {
        mockMvc
            .get("/admin/posts/pending") { header("Authorization", "Bearer $regularUserToken") }
            .andExpect {
                status { isForbidden() }
                jsonPath("$.detail") { value("Insufficient privileges: requires ADMIN") }
            }
    }

    @Test
    fun `GET pending unauthenticated returns 401`() {
        mockMvc.get("/admin/posts/pending").andExpect {
            status { isUnauthorized() }
            jsonPath("$.detail") { value("Authentication required") }
        }
    }

    // --- PUT /admin/posts/{id}/approve ---

    @Test
    fun `PUT approve sets post to APPROVED with publishedAt`() {
        val publishedAt = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS)

        mockMvc
            .put("/admin/posts/${draftPost.id}/approve") {
                contentType = MediaType.APPLICATION_JSON
                header("Authorization", "Bearer $adminToken")
                content = """{"publishedAt":"$publishedAt"}"""
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.status") { value("APPROVED") }
                jsonPath("$.title") { value("Draft for Admin") }
            }
    }

    @Test
    fun `PUT approve by non-admin returns 403`() {
        mockMvc
            .put("/admin/posts/${draftPost.id}/approve") {
                contentType = MediaType.APPLICATION_JSON
                header("Authorization", "Bearer $regularUserToken")
                content = """{"publishedAt":"${Instant.now()}"}"""
            }
            .andExpect {
                status { isForbidden() }
                jsonPath("$.detail") { value("Insufficient privileges: requires ADMIN") }
            }
    }

    @Test
    fun `PUT approve nonexistent post returns 404`() {
        mockMvc
            .put("/admin/posts/00000000-0000-0000-0000-000000000001/approve") {
                contentType = MediaType.APPLICATION_JSON
                header("Authorization", "Bearer $adminToken")
                content = """{"publishedAt":"${Instant.now()}"}"""
            }
            .andExpect {
                status { isNotFound() }
                jsonPath("$.detail") { value("Post not found") }
            }
    }

    // --- PUT /admin/posts/{id} ---

    @Test
    fun `PUT admin edit updates post`() {
        val publishedAt = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
        mockMvc
            .put("/admin/posts/${draftPost.id}") {
                contentType = MediaType.APPLICATION_JSON
                header("Authorization", "Bearer $adminToken")
                content =
                    """{"title":"Admin Edited","markdownSource":"Admin edited content.","publishedAt":"$publishedAt","sortOrder":-5}"""
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.title") { value("Admin Edited") }
                jsonPath("$.sortOrder") { value(-5) }
            }

        val updated = postRepository.findById(draftPost.id).orElseThrow()
        assertEquals(publishedAt, updated.publishedAt)
        assertEquals(-5, updated.sortOrder)
    }

    @Test
    fun `POST derive-tags by admin returns 400 when ai is unavailable`() {
        mockMvc
            .post("/admin/posts/${draftPost.id}/derive-tags") {
                contentType = MediaType.APPLICATION_JSON
                header("Authorization", "Bearer $adminToken")
                content =
                    """{"title":"Admin Edited","markdownSource":"Admin edited content.","existingTags":["java"]}"""
            }
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.detail") { value("AI service unavailable") }
            }
    }

    @Test
    fun `POST derive-tags by non-admin returns 403`() {
        mockMvc
            .post("/admin/posts/${draftPost.id}/derive-tags") {
                contentType = MediaType.APPLICATION_JSON
                header("Authorization", "Bearer $regularUserToken")
                content =
                    """{"title":"Admin Edited","markdownSource":"Admin edited content.","existingTags":["java"]}"""
            }
            .andExpect {
                status { isForbidden() }
                jsonPath("$.detail") { value("Insufficient privileges: requires ADMIN") }
            }
    }

    @Test
    fun `POST derive-tags unauthenticated returns 401`() {
        mockMvc
            .post("/admin/posts/${draftPost.id}/derive-tags") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"title":"Admin Edited","markdownSource":"Admin edited content.","existingTags":["java"]}"""
            }
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.detail") { value("Authentication required") }
            }
    }

    // --- DELETE /admin/posts/{id} ---

    @Test
    fun `DELETE soft-deletes post by default`() {
        mockMvc
            .delete("/admin/posts/${draftPost.id}") {
                contentType = MediaType.APPLICATION_JSON
                header("Authorization", "Bearer $adminToken")
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.id") { value(draftPost.id.toString()) }
                jsonPath("$.message") { value("Post deleted") }
            }

        val updated = postRepository.findById(draftPost.id).orElse(null)
        assertTrue(updated.deleted)
    }

    @Test
    fun `DELETE with hard=true permanently removes post`() {
        val postId = draftPost.id

        mockMvc
            .delete("/admin/posts/$postId?hard=true") {
                contentType = MediaType.APPLICATION_JSON
                header("Authorization", "Bearer $adminToken")
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.id") { value(postId.toString()) }
                jsonPath("$.message") { value("Post permanently removed") }
            }

        assertFalse(postRepository.existsById(postId))
    }

    @Test
    fun `DELETE by non-admin returns 403`() {
        mockMvc
            .delete("/admin/posts/${draftPost.id}") {
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
        mockMvc
            .delete("/admin/posts/${draftPost.id}") { contentType = MediaType.APPLICATION_JSON }
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.detail") { value("Authentication required") }
            }
    }

    @Test
    fun `DELETE nonexistent post returns 404`() {
        mockMvc
            .delete("/admin/posts/00000000-0000-0000-0000-000000000001") {
                contentType = MediaType.APPLICATION_JSON
                header("Authorization", "Bearer $adminToken")
            }
            .andExpect {
                status { isNotFound() }
                jsonPath("$.detail") { value("Post not found") }
            }
    }
}
