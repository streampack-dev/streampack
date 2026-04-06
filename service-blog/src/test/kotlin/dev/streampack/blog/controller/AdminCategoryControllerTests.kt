/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.blog.entity.Category
import dev.streampack.blog.repository.CategoryRepository
import dev.streampack.core.entity.User
import dev.streampack.core.model.Role
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.JwtService
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional

/** Integration tests for admin category management endpoints */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminCategoryControllerTests {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var categoryRepository: CategoryRepository
    @Autowired lateinit var jwtService: JwtService

    private lateinit var adminUser: User
    private lateinit var adminToken: String
    private lateinit var regularUser: User
    private lateinit var regularUserToken: String

    @BeforeEach
    fun setUp() {
        adminUser =
            userRepository.save(
                User(
                    username = "catadmin",
                    email = "catadmin@test.com",
                    displayName = "Category Admin",
                    emailVerified = true,
                    role = Role.ADMIN,
                )
            )
        adminToken = jwtService.generateToken(adminUser.toUserPrincipal())

        regularUser =
            userRepository.save(
                User(
                    username = "catregular",
                    email = "catregular@test.com",
                    displayName = "Regular User",
                    emailVerified = true,
                    role = Role.USER,
                )
            )
        regularUserToken = jwtService.generateToken(regularUser.toUserPrincipal())
    }

    // --- POST /admin/categories ---

    @Test
    fun `POST creates category and returns 201`() {
        mockMvc
            .post("/admin/categories") {
                contentType = MediaType.APPLICATION_JSON
                header("Authorization", "Bearer $adminToken")
                content = """{"name":"Frameworks"}"""
            }
            .andExpect {
                status { isCreated() }
                jsonPath("$.name") { value("Frameworks") }
                jsonPath("$.slug") { value("frameworks") }
                jsonPath("$.id") { isNotEmpty() }
            }
    }

    @Test
    fun `POST by non-admin returns 403`() {
        mockMvc
            .post("/admin/categories") {
                contentType = MediaType.APPLICATION_JSON
                header("Authorization", "Bearer $regularUserToken")
                content = """{"name":"Frameworks"}"""
            }
            .andExpect {
                status { isForbidden() }
                jsonPath("$.detail") { value("Insufficient privileges: requires ADMIN") }
            }
    }

    @Test
    fun `POST unauthenticated returns 401`() {
        mockMvc
            .post("/admin/categories") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Frameworks"}"""
            }
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.detail") { value("Authentication required") }
            }
    }

    @Test
    fun `POST with blank name returns 400`() {
        mockMvc
            .post("/admin/categories") {
                contentType = MediaType.APPLICATION_JSON
                header("Authorization", "Bearer $adminToken")
                content = """{"name":"  "}"""
            }
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.detail") { value("Category name is required") }
            }
    }

    @Test
    fun `POST duplicate name returns 400`() {
        categoryRepository.save(Category(name = "Existing", slug = "existing"))

        mockMvc
            .post("/admin/categories") {
                contentType = MediaType.APPLICATION_JSON
                header("Authorization", "Bearer $adminToken")
                content = """{"name":"Existing"}"""
            }
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.detail") { value("Category name already exists") }
            }
    }

    // --- DELETE /admin/categories/{id} ---

    @Test
    fun `DELETE soft-deletes category`() {
        val category = categoryRepository.save(Category(name = "To Delete", slug = "to-delete"))

        mockMvc
            .delete("/admin/categories/${category.id}") {
                contentType = MediaType.APPLICATION_JSON
                header("Authorization", "Bearer $adminToken")
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.id") { value(category.id.toString()) }
                jsonPath("$.message") { value("Category deleted") }
            }

        val updated = categoryRepository.findById(category.id).orElse(null)
        assertTrue(updated.deleted)
    }

    @Test
    fun `DELETE by non-admin returns 403`() {
        val category = categoryRepository.save(Category(name = "Protected", slug = "protected"))

        mockMvc
            .delete("/admin/categories/${category.id}") {
                contentType = MediaType.APPLICATION_JSON
                header("Authorization", "Bearer $regularUserToken")
            }
            .andExpect {
                status { isForbidden() }
                jsonPath("$.detail") { value("Insufficient privileges: requires ADMIN") }
            }
    }

    @Test
    fun `DELETE nonexistent category returns 404`() {
        mockMvc
            .delete("/admin/categories/00000000-0000-0000-0000-000000000001") {
                contentType = MediaType.APPLICATION_JSON
                header("Authorization", "Bearer $adminToken")
            }
            .andExpect {
                status { isNotFound() }
                jsonPath("$.detail") { value("Category not found") }
            }
    }
}
