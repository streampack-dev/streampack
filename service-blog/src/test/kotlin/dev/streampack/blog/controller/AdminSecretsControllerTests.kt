/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Role
import dev.streampack.core.model.SecretRequirement
import dev.streampack.core.service.JwtService
import dev.streampack.core.service.SecretRequirementSource
import dev.streampack.core.service.UserRegistrationService
import dev.streampack.test.ResetDatabaseBeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/** `GET /admin/secrets`: the variables the next start needs, for super-admins only (#209). */
@SpringBootTest
@AutoConfigureMockMvc
@ResetDatabaseBeforeEach
class AdminSecretsControllerTests {

    @TestConfiguration
    class Sources {
        @Bean
        fun storedMattermostToken(): SecretRequirementSource =
            object : SecretRequirementSource {
                override fun secretRequirements() =
                    listOf(
                        SecretRequirement(
                            "MATTERMOST_WORK_TOKEN",
                            "Mattermost server 'work' token",
                            SecretRequirement.State.STORED_IN_DATABASE,
                        ),
                    )
            }
    }

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var userRegistrationService: UserRegistrationService
    @Autowired lateinit var jwtService: JwtService

    private fun tokenFor(username: String, role: Role): String =
        jwtService.generateToken(
            userRegistrationService.register(
                username = username,
                email = "$username@example.com",
                displayName = username,
                protocol = Protocol.HTTP,
                serviceId = "blog-service",
                externalIdentifier = "$username@example.com",
                role = role,
            ),
        )

    @Test
    fun `lists each variable with what it's for and its state, and no value`() {
        mockMvc
            .get("/admin/secrets") {
                header("Authorization", "Bearer ${tokenFor("root", Role.SUPER_ADMIN)}")
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.required.length()") { value(1) }
                jsonPath("$.required[0].variable") { value("MATTERMOST_WORK_TOKEN") }
                jsonPath("$.required[0].description") { value("Mattermost server 'work' token") }
                jsonPath("$.required[0].state") { value("STORED_IN_DATABASE") }
                jsonPath("$.required[0].value") { doesNotExist() }
            }
    }

    @Test
    fun `is refused to an admin who isn't a super-admin, and to no one at all`() {
        mockMvc
            .get("/admin/secrets") {
                header("Authorization", "Bearer ${tokenFor("admin", Role.ADMIN)}")
            }
            .andExpect { status { isForbidden() } }
        mockMvc.get("/admin/secrets").andExpect { status { isUnauthorized() } }
    }
}
