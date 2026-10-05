/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.controller

import dev.streampack.ai.config.AiProperties
import dev.streampack.ai.service.AiService
import dev.streampack.ai.service.AiStructuredResponse
import dev.streampack.core.entity.User
import dev.streampack.core.model.Role
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.JwtService
import dev.streampack.factoid.model.FactoidAttributeType
import dev.streampack.factoid.operation.DeriveFactoidOperation.Proposal
import dev.streampack.factoid.service.FactoidService
import dev.streampack.test.ResetDatabaseBeforeEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.ai.chat.model.ChatModel
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

/** Drafting a factoid over HTTP (#132), with a stand-in model. */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ResetDatabaseBeforeEach
class FactoidDeriveControllerTests {
    @TestConfiguration
    class Config {
        @Bean
        @Primary
        fun fixedAi(): AiService =
            object : AiService(Mockito.mock(ChatModel::class.java), AiProperties(enabled = true)) {
                @Suppress("UNCHECKED_CAST")
                override fun <T : Any> promptForObjectWithRaw(
                    systemInstruction: String,
                    userPrompt: String,
                    responseType: Class<T>,
                ) =
                    AiStructuredResponse(
                        Proposal(text = "a small OSGi runtime.", tags = listOf("osgi")) as T,
                        "",
                    )
            }
    }

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var factoidService: FactoidService
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var jwtService: JwtService

    private lateinit var token: String

    @BeforeEach
    fun setUp() {
        val user =
            userRepository.save(
                User(
                    username = "writer",
                    email = "writer@test.com",
                    displayName = "writer",
                    emailVerified = true,
                    role = Role.USER,
                )
            )
        token = jwtService.generateToken(user.toUserPrincipal())
        factoidService.save(
            "osgi",
            FactoidAttributeType.TEXT,
            "a module system for Java",
            "someone",
        )
    }

    private fun derive(body: String, signedIn: Boolean = true) =
        mockMvc.post("/factoids/derive") {
            contentType = MediaType.APPLICATION_JSON
            content = body
            if (signedIn) header("Authorization", "Bearer $token")
        }

    @Test
    fun `a signed-in reader gets a draft and the line the bot would say`() {
        derive("""{"selector":"karaf","context":"Karaf runs on OSGi."}""").andExpect {
            status { isOk() }
            jsonPath("$.selector") { value("karaf") }
            jsonPath("$.text") { value("a small OSGi runtime.") }
            jsonPath("$.line") { value("karaf is a small OSGi runtime. Tag: osgi") }
            jsonPath("$.fits") { value(true) }
        }
    }

    @Test
    fun `a name that's taken is a conflict`() {
        derive("""{"selector":"osgi"}""").andExpect { status { isConflict() } }
    }

    @Test
    fun `no name is a bad request, and no sign-in is refused`() {
        derive("""{"selector":" "}""").andExpect { status { isBadRequest() } }
        derive("""{"selector":"karaf"}""", signedIn = false).andExpect {
            status { isUnauthorized() }
        }
    }
}
