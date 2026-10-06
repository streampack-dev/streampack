/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.controller

import dev.streampack.core.entity.User
import dev.streampack.core.model.Role
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.JwtService
import dev.streampack.factoid.model.FactoidAttributeType
import dev.streampack.factoid.service.FactoidService
import dev.streampack.test.ResetDatabaseBeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/** The factoid graph report over HTTP, for admins (#133). */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ResetDatabaseBeforeEach
class AdminFactoidControllerTests {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var factoidService: FactoidService

    private fun token(username: String, role: Role): String =
        jwtService.generateToken(
            userRepository
                .save(
                    User(
                        username = username,
                        email = "$username@test.com",
                        displayName = username,
                        emailVerified = true,
                        role = role,
                    )
                )
                .toUserPrincipal()
        )

    @Test
    fun `admins get the report`() {
        factoidService.save(
            "osgi",
            FactoidAttributeType.TEXT,
            "a module system for Java",
            "someone",
        )
        factoidService.save("osgi", FactoidAttributeType.SEEALSO, "karaf", "someone")
        factoidService.save("karaf", FactoidAttributeType.TEXT, "an OSGi runtime", "someone")

        mockMvc
            .get("/admin/factoids/report") {
                header("Authorization", "Bearer ${token("keeper", Role.ADMIN)}")
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.parentsNotLinked[0].selector") { value("karaf") }
                jsonPath("$.parentsNotLinked[0].mentions[0]") { value("osgi") }
            }
    }

    @Test
    fun `no one else does`() {
        mockMvc.get("/admin/factoids/report").andExpect { status { isUnauthorized() } }
        mockMvc
            .get("/admin/factoids/report") {
                header("Authorization", "Bearer ${token("reader", Role.USER)}")
            }
            .andExpect { status { isForbidden() } }
    }
}
