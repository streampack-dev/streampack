/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.webconsole

import dev.streampack.core.model.Role
import dev.streampack.core.model.UserStatus
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.JwtService
import dev.streampack.core.service.ThrottleService
import dev.streampack.test.ResetDatabaseBeforeEach
import dev.streampack.web.auth.AuthCookieNames
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * Who may use the web console, and what a command request must look like (#115). Nothing here is
 * dispatched unless every check passes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ResetDatabaseBeforeEach
@TestPropertySource(
    properties =
        [
            "streampack.webconsole.commands-per-minute=3",
            "streampack.webconsole.max-streams-per-user=2",
            "streampack.webconsole.max-line=40",
            "streampack.webconsole.max-body=512",
            "CORS_ORIGINS=https://trusted.example",
        ]
)
class WebConsoleAccessTests {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var throttleService: ThrottleService
    @Autowired lateinit var streams: WebConsoleStreams

    private lateinit var console: WebConsoleTestSupport
    private lateinit var adminToken: String
    private lateinit var userToken: String

    @BeforeEach
    fun setUp() {
        throttleService.clear()
        console = WebConsoleTestSupport(mockMvc, userRepository, jwtService)
        adminToken = console.token(console.account("consoleadmin", Role.ADMIN))
        userToken = console.token(console.account("consoleuser", Role.USER))
    }

    @AfterEach
    fun tearDown() {
        streams.closeAll()
    }

    private fun post(
        build: org.springframework.test.web.servlet.MockHttpServletRequestDsl.() -> Unit
    ) =
        mockMvc.post("/admin/console") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"line":"hello"}"""
            build()
        }

    @Test
    fun `only an active admin may run commands or open the stream`() {
        post { header(WebConsoleController.HEADER, "1") }.andExpect { status { isUnauthorized() } }
        post {
            header("Authorization", "Bearer $userToken")
            header(WebConsoleController.HEADER, "1")
        }
            .andExpect { status { isForbidden() } }
        mockMvc.get("/admin/console/stream").andExpect { status { isUnauthorized() } }
        mockMvc
            .get("/admin/console/stream") { header("Authorization", "Bearer $userToken") }
            .andExpect { status { isForbidden() } }
    }

    @Test
    fun `an admin's credential counts only while the account is still an admin`() {
        val admin = console.account("demoted", Role.ADMIN)
        val token = console.token(admin)
        userRepository.save(admin.copy(role = Role.USER))
        console.submit(token, "hello").andExpect { status { isForbidden() } }

        val suspended = console.account("suspended", Role.ADMIN)
        val suspendedToken = console.token(suspended)
        userRepository.save(suspended.copy(status = UserStatus.SUSPENDED))
        console.submit(suspendedToken, "hello").andExpect { status { isForbidden() } }
    }

    @Test
    fun `a command needs the console header`() {
        post { header("Authorization", "Bearer $adminToken") }
            .andExpect { status { isForbidden() } }
    }

    @Test
    fun `a cookie-authenticated command must come from a trusted origin`() {
        val cookie = Cookie(AuthCookieNames.ACCESS_TOKEN, adminToken)
        // No source at all: a cookie is sent by the browser on its own, so it's not enough.
        post {
            cookie(cookie)
            header(WebConsoleController.HEADER, "1")
        }
            .andExpect { status { isForbidden() } }
        for (origin in listOf("https://evil.example", "null")) {
            post {
                cookie(cookie)
                header(WebConsoleController.HEADER, "1")
                header("Origin", origin)
            }
                .andExpect { status { isForbidden() } }
        }
        post {
            cookie(cookie)
            header(WebConsoleController.HEADER, "1")
            header("Origin", "https://trusted.example")
        }
            .andExpect { status { isAccepted() } }
        post {
            cookie(cookie)
            header(WebConsoleController.HEADER, "1")
            header("Referer", "https://trusted.example/admin/console")
        }
            .andExpect { status { isAccepted() } }
    }

    @Test
    fun `a bearer-authenticated command needs no origin, but may not name an untrusted one`() {
        post {
            header("Authorization", "Bearer $adminToken")
            header(WebConsoleController.HEADER, "1")
        }
            .andExpect { status { isAccepted() } }
        post {
            header("Authorization", "Bearer $adminToken")
            header(WebConsoleController.HEADER, "1")
            header("Origin", "https://evil.example")
        }
            .andExpect { status { isForbidden() } }
    }

    @Test
    fun `a valid cookie wins over a bearer header, and is checked as a cookie`() {
        // The cookie names a regular user; the bearer names an admin. The cookie is used.
        post {
            cookie(Cookie(AuthCookieNames.ACCESS_TOKEN, userToken))
            header("Authorization", "Bearer $adminToken")
            header(WebConsoleController.HEADER, "1")
            header("Origin", "https://trusted.example")
        }
            .andExpect { status { isForbidden() } }
        // An invalid cookie falls back to the bearer, which needs no origin.
        post {
            cookie(Cookie(AuthCookieNames.ACCESS_TOKEN, "not-a-token"))
            header("Authorization", "Bearer $adminToken")
            header(WebConsoleController.HEADER, "1")
        }
            .andExpect { status { isAccepted() } }
    }

    @Test
    fun `a command is one line of a bounded length, in a JSON body`() {
        for (line in
            listOf("", "   ", "two\nlines", "carriage\rreturn", "nul\u0000here", "x".repeat(41))) {
            console.submit(adminToken, line).andExpect { status { isBadRequest() } }
        }
        post {
            header("Authorization", "Bearer $adminToken")
            header(WebConsoleController.HEADER, "1")
            content = "not json"
        }
            .andExpect { status { isBadRequest() } }
        post {
            header("Authorization", "Bearer $adminToken")
            header(WebConsoleController.HEADER, "1")
            content = """{"line":"${"x".repeat(600)}"}"""
        }
            .andExpect { status { isPayloadTooLarge() } }
        mockMvc
            .post("/admin/console") {
                header("Authorization", "Bearer $adminToken")
                header(WebConsoleController.HEADER, "1")
                contentType = MediaType.TEXT_PLAIN
                content = "hello"
            }
            .andExpect { status { isUnsupportedMediaType() } }
        // Internal spacing is the command's own, and kept.
        console.submit(adminToken, "  a   spaced  line ").andExpect { status { isAccepted() } }
    }

    @Test
    fun `commands are limited per admin, across tokens`() {
        val admin = userRepository.findAll().first { it.username == "consoleadmin" }
        val other = console.token(admin)
        repeat(2) { console.submit(adminToken, "hello").andExpect { status { isAccepted() } } }
        console.submit(other, "hello").andExpect { status { isAccepted() } }
        console.submit(other, "hello").andExpect { status { isTooManyRequests() } }
        console.submit(adminToken, "hello").andExpect { status { isTooManyRequests() } }
    }

    @Test
    fun `an accepted command is answered with its correlation id, uncached`() {
        val response = console.submit(adminToken, "hello").andReturn().response
        assertEquals(202, response.status)
        assertEquals("no-store", response.getHeader("Cache-Control"))
        assertTrue(response.contentAsString.contains("correlationId"))
    }

    @Test
    fun `the stream is an uncached, unbuffered event stream, limited in number per admin`() {
        val first = console.open(adminToken)
        assertEquals("no-store", first.response.getHeader("Cache-Control"))
        assertEquals("no", first.response.getHeader("X-Accel-Buffering"))
        assertTrue(first.response.contentType!!.startsWith("text/event-stream"))
        assertEquals(
            "consoleadmin",
            console.events(first).first { it.name == "ready" }.field("username"),
        )
        console.open(adminToken)
        mockMvc
            .get("/admin/console/stream") { header("Authorization", "Bearer $adminToken") }
            .andExpect { status { isTooManyRequests() } }
    }
}
