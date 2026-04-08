/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.core.entity.User
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserStatus
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.JwtService
import dev.streampack.core.service.UserRegistrationService
import dev.streampack.test.ResetDatabaseBeforeEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put

/** Integration tests for admin user management endpoints, verifying privilege enforcement */
@SpringBootTest
@AutoConfigureMockMvc
@ResetDatabaseBeforeEach
class AdminUserControllerTests {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var userRegistrationService: UserRegistrationService
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userRepository: UserRepository

    private lateinit var superAdminToken: String
    private lateinit var adminToken: String
    private lateinit var regularUserToken: String

    @BeforeEach
    fun setUp() {
        val superAdminPrincipal =
            userRegistrationService.register(
                username = "superadmin",
                email = "superadmin@example.com",
                displayName = "Super Admin",
                protocol = Protocol.HTTP,
                serviceId = "blog-service",
                externalIdentifier = "superadmin@example.com",
                role = Role.SUPER_ADMIN,
            )
        superAdminToken = jwtService.generateToken(superAdminPrincipal)

        val adminPrincipal =
            userRegistrationService.register(
                username = "testadmin",
                email = "testadmin@example.com",
                displayName = "Test Admin",
                protocol = Protocol.HTTP,
                serviceId = "blog-service",
                externalIdentifier = "testadmin@example.com",
                role = Role.ADMIN,
            )
        adminToken = jwtService.generateToken(adminPrincipal)

        val regularPrincipal =
            userRegistrationService.register(
                username = "regular",
                email = "regular@example.com",
                displayName = "Regular",
                protocol = Protocol.HTTP,
                serviceId = "blog-service",
                externalIdentifier = "regular@example.com",
            )
        regularUserToken = jwtService.generateToken(regularPrincipal)
    }

    @Test
    fun `super admin can change user role`() {
        mockMvc
            .put("/admin/users/regular/role") {
                contentType = MediaType.APPLICATION_JSON
                header("Authorization", "Bearer $superAdminToken")
                content = """{"newRole":"ADMIN"}"""
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.role") { value("ADMIN") }
                jsonPath("$.username") { value("regular") }
            }
    }

    @Test
    fun `non-super-admin gets 403 on role change`() {
        mockMvc
            .put("/admin/users/regular/role") {
                contentType = MediaType.APPLICATION_JSON
                header("Authorization", "Bearer $adminToken")
                content = """{"newRole":"ADMIN"}"""
            }
            .andExpect {
                status { isForbidden() }
                jsonPath("$.detail") { value("Insufficient privileges") }
            }
    }

    @Test
    fun `unauthenticated role change returns 401`() {
        mockMvc
            .put("/admin/users/regular/role") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"newRole":"ADMIN"}"""
            }
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.detail") { value("Not authenticated") }
            }
    }

    @Test
    fun `regular user gets 403 on role change`() {
        mockMvc
            .put("/admin/users/testadmin/role") {
                contentType = MediaType.APPLICATION_JSON
                header("Authorization", "Bearer $regularUserToken")
                content = """{"newRole":"ADMIN"}"""
            }
            .andExpect {
                status { isForbidden() }
                jsonPath("$.detail") { value("Insufficient privileges: requires ADMIN") }
            }
    }

    @Test
    fun `role change for nonexistent user returns 400`() {
        mockMvc
            .put("/admin/users/nobody/role") {
                contentType = MediaType.APPLICATION_JSON
                header("Authorization", "Bearer $superAdminToken")
                content = """{"newRole":"ADMIN"}"""
            }
            .andExpect { status { isBadRequest() } }
    }

    @Test
    fun `super admin can promote user to admin`() {
        mockMvc
            .put("/admin/users/regular/role") {
                contentType = MediaType.APPLICATION_JSON
                header("Authorization", "Bearer $superAdminToken")
                content = """{"newRole":"ADMIN"}"""
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.role") { value("ADMIN") }
            }
    }

    @Test
    fun `super admin can demote admin to user`() {
        mockMvc
            .put("/admin/users/testadmin/role") {
                contentType = MediaType.APPLICATION_JSON
                header("Authorization", "Bearer $superAdminToken")
                content = """{"newRole":"USER"}"""
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.role") { value("USER") }
            }
    }

    @Test
    fun `admin cannot promote to super admin`() {
        mockMvc
            .put("/admin/users/regular/role") {
                contentType = MediaType.APPLICATION_JSON
                header("Authorization", "Bearer $adminToken")
                content = """{"newRole":"SUPER_ADMIN"}"""
            }
            .andExpect { status { isForbidden() } }
    }

    @Test
    fun `admin can suspend and unsuspend active user`() {
        mockMvc
            .put("/admin/users/regular/suspend") { header("Authorization", "Bearer $adminToken") }
            .andExpect { status { isOk() } }
        mockMvc
            .put("/admin/users/regular/unsuspend") { header("Authorization", "Bearer $adminToken") }
            .andExpect { status { isOk() } }
    }

    @Test
    fun `non-admin cannot suspend user`() {
        mockMvc
            .put("/admin/users/testadmin/suspend") {
                header("Authorization", "Bearer $regularUserToken")
            }
            .andExpect {
                status { isForbidden() }
                jsonPath("$.detail") { value("Insufficient privileges: requires ADMIN") }
            }
    }

    @Test
    fun `suspend unauthenticated returns 401`() {
        mockMvc.put("/admin/users/regular/suspend").andExpect {
            status { isUnauthorized() }
            jsonPath("$.detail") { value("Not authenticated") }
        }
    }

    @Test
    fun `malformed auth header returns 401`() {
        mockMvc
            .put("/admin/users/regular/suspend") { header("Authorization", "Token $adminToken") }
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.detail") { value("Not authenticated") }
            }
    }

    @Test
    fun `admin can erase a regular user account`() {
        mockMvc
            .delete("/admin/users/regular") { header("Authorization", "Bearer $adminToken") }
            .andExpect { status { isOk() } }
    }

    @Test
    fun `purge erased content returns 404 when user does not exist`() {
        mockMvc
            .delete("/admin/users/nope/purge") { header("Authorization", "Bearer $adminToken") }
            .andExpect {
                status { isNotFound() }
                jsonPath("$.detail") { value("User not found") }
            }
    }

    @Test
    fun `admin can purge erased sentinel user`() {
        userRepository.save(
            User(
                username = "erased-test",
                email = "",
                displayName = "[deleted]",
                role = Role.GUEST,
                status = UserStatus.ERASED,
            )
        )

        mockMvc
            .delete("/admin/users/erased-test/purge") {
                header("Authorization", "Bearer $adminToken")
            }
            .andExpect { status { isOk() } }
    }

    @Test
    fun `list by status returns erased users for admin`() {
        userRepository.save(
            User(
                username = "erased-visible",
                email = "",
                displayName = "[deleted]",
                role = Role.GUEST,
                status = UserStatus.ERASED,
            )
        )

        mockMvc
            .get("/admin/users?status=ERASED") { header("Authorization", "Bearer $adminToken") }
            .andExpect {
                status { isOk() }
                jsonPath("$[?(@.username == 'erased-visible')]") { exists() }
            }
    }

    @Test
    fun `list by status non-admin returns 403`() {
        mockMvc
            .get("/admin/users?status=ERASED") {
                header("Authorization", "Bearer $regularUserToken")
            }
            .andExpect {
                status { isForbidden() }
                jsonPath("$.detail") { value("Insufficient privileges") }
            }
    }

    @Test
    fun `list by status unauthenticated returns 401`() {
        mockMvc.get("/admin/users?status=ERASED").andExpect {
            status { isUnauthorized() }
            jsonPath("$.detail") { value("Not authenticated") }
        }
    }

    @Test
    fun `admin can export user data`() {
        mockMvc
            .get("/admin/users/regular/export") { header("Authorization", "Bearer $adminToken") }
            .andExpect {
                status { isOk() }
                jsonPath("$.profile.username") { value("regular") }
            }
    }

    @Test
    fun `export by non-admin returns 403`() {
        mockMvc
            .get("/admin/users/testadmin/export") {
                header("Authorization", "Bearer $regularUserToken")
            }
            .andExpect {
                status { isForbidden() }
                jsonPath("$.detail") { value("Insufficient privileges") }
            }
    }

    @Test
    fun `export missing user returns 400`() {
        mockMvc
            .get("/admin/users/not-a-user/export") { header("Authorization", "Bearer $adminToken") }
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.detail") { value("User not found") }
            }
    }
}
