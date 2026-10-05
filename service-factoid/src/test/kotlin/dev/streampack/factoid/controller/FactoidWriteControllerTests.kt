/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.controller

import dev.streampack.core.entity.User
import dev.streampack.core.model.Role
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.JwtService
import dev.streampack.factoid.model.FactoidAttributeType
import dev.streampack.factoid.repository.FactoidRepository
import dev.streampack.factoid.service.FactoidService
import dev.streampack.test.ResetDatabaseBeforeEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
import org.springframework.test.web.servlet.put

/**
 * Setting, forgetting and locking factoids over HTTP (#99). The production ingress channel is used
 * (no TestChannelConfiguration), so these run as a request does: the operation on another thread,
 * and the setter's follow-up event alongside.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ResetDatabaseBeforeEach
class FactoidWriteControllerTests {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var factoidService: FactoidService
    @Autowired lateinit var factoidRepository: FactoidRepository
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var jwtService: JwtService

    private lateinit var userToken: String
    private lateinit var adminToken: String

    @BeforeEach
    fun setUp() {
        userToken = token("writer", Role.USER)
        adminToken = token("keeper", Role.ADMIN)
        factoidService.save("spring", FactoidAttributeType.TEXT, "A Java framework", "someone")
    }

    private fun token(username: String, role: Role): String {
        val user =
            userRepository.save(
                User(
                    username = username,
                    email = "$username@test.com",
                    displayName = username,
                    emailVerified = true,
                    role = role,
                )
            )
        return jwtService.generateToken(user.toUserPrincipal())
    }

    private fun setText(selector: String, body: String, token: String? = userToken) =
        mockMvc.put("/factoids/$selector") {
            contentType = MediaType.APPLICATION_JSON
            content = body
            if (token != null) header("Authorization", "Bearer $token")
        }

    @Test
    fun `a signed-in user creates a factoid, attributed to them`() {
        setText("aho-corasick", """{"value":"a string-searching algorithm"}""").andExpect {
            status { isCreated() }
            jsonPath("$.selector") { value("aho-corasick") }
            jsonPath("$.updatedBy") { value("writer") }
            jsonPath("$.attributes[0].type") { value("text") }
            jsonPath("$.attributes[0].value") { value("a string-searching algorithm") }
        }
        assertEquals(
            "writer",
            factoidRepository.findBySelectorIgnoreCase("aho-corasick")?.updatedBy,
        )
    }

    @Test
    fun `setting an existing factoid replaces its text`() {
        setText("spring", """{"value":"A framework for Java"}""").andExpect {
            status { isOk() }
            jsonPath("$.attributes[0].value") { value("A framework for Java") }
        }
    }

    @Test
    fun `a named attribute is set, and a selector that reads like a command is taken as it is`() {
        setText("spring", """{"value":"https://spring.io","attribute":"urls"}""").andExpect {
            status { isOk() }
        }
        assertTrue(
            factoidService.findBySelector("spring").any {
                it.attributeType == FactoidAttributeType.URLS
            }
        )

        // Chat would read "x is y=z" as an assignment; a typed request never parses it.
        setText("x is y", """{"value":"z=1"}""").andExpect { status { isCreated() } }
        assertNotNull(factoidRepository.findBySelectorIgnoreCase("x is y"))
    }

    @Test
    fun `anyone not signed in is refused`() {
        setText("spring", """{"value":"anything"}""", token = null).andExpect {
            status { isUnauthorized() }
        }
        mockMvc.delete("/factoids/spring").andExpect { status { isUnauthorized() } }
        mockMvc.put("/factoids/spring/lock").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `a blank value, an attribute that can't be set, or an overlong name is a 400`() {
        setText("spring", """{"value":"  "}""").andExpect { status { isBadRequest() } }
        setText("spring", """{"value":"x","attribute":"lock"}""").andExpect {
            status { isBadRequest() }
        }
        setText("spring", """{"value":"x","attribute":"nonsense"}""").andExpect {
            status { isBadRequest() }
        }
        setText("a".repeat(201), """{"value":"x"}""").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `a locked factoid can't be set or forgotten, by anyone`() {
        factoidService.setLocked("spring", true)

        setText("spring", """{"value":"changed"}""").andExpect {
            status { isConflict() }
            jsonPath("$.detail") { value("Factoid 'spring' is locked.") }
        }
        setText("spring", """{"value":"changed"}""", token = adminToken).andExpect {
            status { isConflict() }
        }
        mockMvc
            .delete("/factoids/spring") { header("Authorization", "Bearer $userToken") }
            .andExpect {
                status { isConflict() }
            }
        assertNotNull(factoidRepository.findBySelectorIgnoreCase("spring"))
    }

    @Test
    fun `forgetting removes a factoid, or one attribute of it`() {
        factoidService.save("spring", FactoidAttributeType.URLS, "https://spring.io", "someone")

        mockMvc
            .delete("/factoids/spring?attribute=urls") {
                header("Authorization", "Bearer $userToken")
            }
            .andExpect {
                status { isNoContent() }
            }
        assertTrue(
            factoidService.findBySelector("spring").none {
                it.attributeType == FactoidAttributeType.URLS
            }
        )

        mockMvc
            .delete("/factoids/spring") { header("Authorization", "Bearer $userToken") }
            .andExpect {
                status { isNoContent() }
            }
        assertNull(factoidRepository.findBySelectorIgnoreCase("spring"))

        mockMvc
            .delete("/factoids/spring") { header("Authorization", "Bearer $userToken") }
            .andExpect {
                status { isNotFound() }
            }
    }

    @Test
    fun `only an admin locks and unlocks, and only the factoid named`() {
        mockMvc
            .put("/factoids/spring/lock") { header("Authorization", "Bearer $userToken") }
            .andExpect {
                status { isForbidden() }
            }
        assertEquals(false, factoidRepository.findBySelectorIgnoreCase("spring")?.locked)

        mockMvc
            .put("/factoids/spring/lock") { header("Authorization", "Bearer $adminToken") }
            .andExpect {
                status { isNoContent() }
            }
        assertEquals(true, factoidRepository.findBySelectorIgnoreCase("spring")?.locked)

        // "spring boot" isn't a factoid; chat's lookup would take it as "spring" given "boot".
        mockMvc
            .put("/factoids/spring boot/lock") { header("Authorization", "Bearer $adminToken") }
            .andExpect {
                status { isNotFound() }
            }

        mockMvc
            .delete("/factoids/spring/lock") { header("Authorization", "Bearer $adminToken") }
            .andExpect {
                status { isNoContent() }
            }
        assertEquals(false, factoidRepository.findBySelectorIgnoreCase("spring")?.locked)
    }

    @Test
    fun `drafting without AI configured is unavailable`() {
        mockMvc
            .post("/factoids/derive") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"selector":"karaf"}"""
                header("Authorization", "Bearer $userToken")
            }
            .andExpect { status { isServiceUnavailable() } }
    }
}
