/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import com.icegreen.greenmail.configuration.GreenMailConfiguration
import com.icegreen.greenmail.junit5.GreenMailExtension
import com.icegreen.greenmail.util.ServerSetupTest
import dev.streampack.blog.service.CookieService
import dev.streampack.core.entity.OneTimeCode
import dev.streampack.core.entity.User
import dev.streampack.core.model.Protocol
import dev.streampack.core.repository.OneTimeCodeRepository
import dev.streampack.core.repository.RefreshTokenRepository
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.JwtService
import dev.streampack.core.service.RefreshTokenService
import dev.streampack.core.service.UserRegistrationService
import jakarta.servlet.http.Cookie
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.*
import org.springframework.transaction.annotation.Transactional

/** Integration tests for /auth endpoints, exercising the full MVC stack */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthControllerTests {

    companion object {
        @JvmField
        @RegisterExtension
        val greenMail: GreenMailExtension =
            GreenMailExtension(ServerSetupTest.SMTP)
                .withConfiguration(GreenMailConfiguration.aConfig().withDisabledAuthentication())
    }

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var userRegistrationService: UserRegistrationService
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var oneTimeCodeRepository: OneTimeCodeRepository
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var refreshTokenService: RefreshTokenService
    @Autowired lateinit var refreshTokenRepository: RefreshTokenRepository

    private lateinit var testUser: User
    private lateinit var testUserToken: String

    @BeforeEach
    fun setUp() {
        greenMail.reset()
        val principal =
            userRegistrationService.register(
                username = "testuser",
                email = "test@example.com",
                displayName = "Test User",
                protocol = Protocol.HTTP,
                serviceId = "blog-service",
                externalIdentifier = "test@example.com",
            )
        testUser = userRepository.findByUsername("testuser")!!
        testUserToken = jwtService.generateToken(principal)
    }

    private fun seedCode(
        email: String,
        code: String,
        expiresAt: Instant = Instant.now().plusSeconds(300),
    ) {
        oneTimeCodeRepository.saveAndFlush(
            OneTimeCode(email = email, code = code, expiresAt = expiresAt)
        )
    }

    /* ── OTP Request ────────────────────────────────────── */

    @Test
    fun `otp request returns 202 and sends email`() {
        mockMvc
            .post("/auth/otp/request") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email":"test@example.com"}"""
            }
            .andExpect { status { isAccepted() } }

        val messages = greenMail.receivedMessages
        assertEquals(1, messages.size)
        assertTrue(messages[0].subject.contains("sign-in code"))
    }

    @Test
    fun `otp request for unknown email still returns 202`() {
        mockMvc
            .post("/auth/otp/request") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email":"nobody@example.com"}"""
            }
            .andExpect { status { isAccepted() } }
    }

    /* ── OTP Verify ─────────────────────────────────────── */

    @Test
    fun `valid otp verify returns token and principal`() {
        seedCode("test@example.com", "123456")

        mockMvc
            .post("/auth/otp/verify") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email":"test@example.com","code":"123456"}"""
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.token") { isNotEmpty() }
                jsonPath("$.principal.username") { value("testuser") }
            }
    }

    @Test
    fun `otp verify creates new user for unknown email`() {
        seedCode("brand-new@example.com", "654321")

        mockMvc
            .post("/auth/otp/verify") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email":"brand-new@example.com","code":"654321"}"""
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.token") { isNotEmpty() }
                jsonPath("$.principal.username") { value("brand-new") }
            }
    }

    @Test
    fun `valid otp verify sets httpOnly cookies`() {
        seedCode("test@example.com", "111111")

        val result =
            mockMvc
                .post("/auth/otp/verify") {
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"email":"test@example.com","code":"111111"}"""
                }
                .andExpect { status { isOk() } }
                .andReturn()

        val setCookieHeaders = result.response.getHeaders("Set-Cookie")
        assertTrue(
            setCookieHeaders.any {
                it.contains(CookieService.ACCESS_TOKEN_COOKIE) && it.contains("HttpOnly")
            }
        )
        assertTrue(
            setCookieHeaders.any {
                it.contains(CookieService.REFRESH_TOKEN_COOKIE) && it.contains("HttpOnly")
            }
        )
    }

    @Test
    fun `invalid otp code returns 401`() {
        seedCode("test@example.com", "123456")

        mockMvc
            .post("/auth/otp/verify") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email":"test@example.com","code":"999999"}"""
            }
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.detail") { value("Invalid or expired code") }
            }
    }

    @Test
    fun `expired otp code returns 401`() {
        seedCode("test@example.com", "123456", expiresAt = Instant.now().minusSeconds(60))

        mockMvc
            .post("/auth/otp/verify") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email":"test@example.com","code":"123456"}"""
            }
            .andExpect { status { isUnauthorized() } }
    }

    /* ── Logout ─────────────────────────────────────────── */

    @Test
    fun `logout returns 204 and clears cookies`() {
        val result =
            mockMvc
                .post("/auth/logout") {
                    cookie(Cookie(CookieService.ACCESS_TOKEN_COOKIE, testUserToken))
                }
                .andExpect { status { isNoContent() } }
                .andReturn()

        val setCookieHeaders = result.response.getHeaders("Set-Cookie")
        assertTrue(
            setCookieHeaders.any {
                it.contains(CookieService.ACCESS_TOKEN_COOKIE) && it.contains("Max-Age=0")
            }
        )
        assertTrue(
            setCookieHeaders.any {
                it.contains(CookieService.REFRESH_TOKEN_COOKIE) && it.contains("Max-Age=0")
            }
        )
    }

    @Test
    fun `logout revokes refresh tokens`() {
        val rawRefreshToken = refreshTokenService.issueToken(testUser.id)
        assertNotNull(
            refreshTokenRepository.findByTokenHash(RefreshTokenService.sha256(rawRefreshToken))
        )

        mockMvc
            .post("/auth/logout") {
                cookie(Cookie(CookieService.ACCESS_TOKEN_COOKIE, testUserToken))
            }
            .andExpect { status { isNoContent() } }

        assertNull(
            refreshTokenRepository.findByTokenHash(RefreshTokenService.sha256(rawRefreshToken))
        )
    }

    /* ── Token Refresh (cookie-based) ──────────────────── */

    @Test
    fun `valid refresh token cookie returns new token and sets cookies`() {
        val rawRefreshToken = refreshTokenService.issueToken(testUser.id)

        val result =
            mockMvc
                .post("/auth/refresh") {
                    cookie(Cookie(CookieService.REFRESH_TOKEN_COOKIE, rawRefreshToken))
                }
                .andExpect {
                    status { isOk() }
                    jsonPath("$.token") { isNotEmpty() }
                    jsonPath("$.principal.username") { value("testuser") }
                }
                .andReturn()

        val setCookieHeaders = result.response.getHeaders("Set-Cookie")
        assertTrue(
            setCookieHeaders.any {
                it.contains(CookieService.ACCESS_TOKEN_COOKIE) && it.contains("HttpOnly")
            }
        )
        assertTrue(
            setCookieHeaders.any {
                it.contains(CookieService.REFRESH_TOKEN_COOKIE) && it.contains("HttpOnly")
            }
        )
    }

    @Test
    fun `refresh with invalid cookie returns 401`() {
        mockMvc
            .post("/auth/refresh") {
                cookie(Cookie(CookieService.REFRESH_TOKEN_COOKIE, "not-a-valid-token"))
            }
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.detail") { value("Invalid or expired refresh token") }
            }
    }

    @Test
    fun `refresh without cookie returns 401`() {
        mockMvc.post("/auth/refresh").andExpect {
            status { isUnauthorized() }
            jsonPath("$.detail") { value("Missing refresh token") }
        }
    }

    @Test
    fun `refresh token is rotated after use`() {
        val rawRefreshToken = refreshTokenService.issueToken(testUser.id)

        mockMvc
            .post("/auth/refresh") {
                cookie(Cookie(CookieService.REFRESH_TOKEN_COOKIE, rawRefreshToken))
            }
            .andExpect { status { isOk() } }

        mockMvc
            .post("/auth/refresh") {
                cookie(Cookie(CookieService.REFRESH_TOKEN_COOKIE, rawRefreshToken))
            }
            .andExpect { status { isUnauthorized() } }
    }

    /* ── Delete Account ─────────────────────────────────── */

    @Test
    fun `authenticated user can delete own account`() {
        mockMvc
            .delete("/auth/account") {
                contentType = MediaType.APPLICATION_JSON
                header("Authorization", "Bearer $testUserToken")
                content = """{}"""
            }
            .andExpect { status { isOk() } }

        // Original user is hard-deleted
        assertNull(userRepository.findByUsername("testuser"))

        // Sentinel was created with erased status
        val sentinelUsername = "erased-${testUser.id.toString().substring(0, 8)}"
        val sentinel = userRepository.findByUsername(sentinelUsername)
        assertNotNull(sentinel)
        assertTrue(sentinel!!.isErased())
    }

    @Test
    fun `delete account without auth returns 401`() {
        mockMvc
            .delete("/auth/account") {
                contentType = MediaType.APPLICATION_JSON
                content = """{}"""
            }
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.detail") { value("Not authenticated") }
            }
    }

    /* ── Profile ───────────────────────────────────────── */

    @Test
    fun `authenticated user can update display name`() {
        mockMvc
            .put("/auth/profile") {
                contentType = MediaType.APPLICATION_JSON
                header("Authorization", "Bearer $testUserToken")
                content = """{"displayName":"Updated User"}"""
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.displayName") { value("Updated User") }
            }

        val updated = userRepository.findByUsername("testuser")
        assertEquals("Updated User", updated?.displayName)
    }

    @Test
    fun `profile update without auth returns 401`() {
        mockMvc
            .put("/auth/profile") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"displayName":"Updated User"}"""
            }
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.detail") { value("Not authenticated") }
            }
    }

    @Test
    fun `session endpoint returns principal for authenticated user via header`() {
        mockMvc
            .get("/auth/session") { header("Authorization", "Bearer $testUserToken") }
            .andExpect {
                status { isOk() }
                jsonPath("$.username") { value("testuser") }
                jsonPath("$.displayName") { value("Test User") }
            }
    }

    @Test
    fun `session endpoint returns principal for authenticated user via cookie`() {
        mockMvc
            .get("/auth/session") {
                cookie(Cookie(CookieService.ACCESS_TOKEN_COOKIE, testUserToken))
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.username") { value("testuser") }
                jsonPath("$.displayName") { value("Test User") }
            }
    }

    @Test
    fun `session endpoint without auth returns 401`() {
        mockMvc.get("/auth/session").andExpect {
            status { isUnauthorized() }
            jsonPath("$.detail") { value("Not authenticated") }
        }
    }
}
