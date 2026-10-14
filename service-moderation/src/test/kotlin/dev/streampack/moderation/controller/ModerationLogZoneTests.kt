/* Joseph B. Ottinger (C)2026 */
package dev.streampack.moderation.controller

import dev.streampack.core.entity.MessageLog
import dev.streampack.core.entity.User
import dev.streampack.core.model.Role
import dev.streampack.core.repository.MessageLogRepository
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.JwtService
import dev.streampack.test.ResetDatabaseBeforeEach
import java.time.Instant
import org.hamcrest.Matchers.contains
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/** The admin's log day follows the configured log zone, like the public one (#212) */
@SpringBootTest(properties = ["streampack.logs.zone=America/New_York"])
@AutoConfigureMockMvc(addFilters = false)
@ResetDatabaseBeforeEach
class ModerationLogZoneTests {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var messageLog: MessageLogRepository

    private val uri = "irc://libera/%23zoned"
    private lateinit var adminToken: String

    @BeforeEach
    fun setUp() {
        adminToken =
            jwtService.generateToken(
                userRepository
                    .save(
                        User(
                            username = "zonekeeper",
                            email = "zonekeeper@test.com",
                            displayName = "zonekeeper",
                            emailVerified = true,
                            role = Role.ADMIN,
                        ),
                    )
                    .toUserPrincipal(),
            )
        for ((content, at) in
            listOf(
                "late on the thirteenth" to "2026-10-14T03:00:00Z",
                "early on the fourteenth" to "2026-10-14T05:00:00Z",
            )) {
            messageLog.save(
                MessageLog(
                    provenanceUri = uri,
                    sender = "bob",
                    content = content,
                    timestamp = Instant.parse(at),
                ),
            )
        }
    }

    @Test
    fun `a day's window and each line's day are in the configured zone`() {
        mockMvc
            .get("/admin/moderation/logs") {
                param("provenance", uri)
                param("day", "2026-10-13")
                header("Authorization", "Bearer $adminToken")
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.lines[*].content") { value(contains("late on the thirteenth")) }
                jsonPath("$.lines[*].day") { value(contains("2026-10-13")) }
            }
        mockMvc
            .get("/admin/moderation/logs") {
                param("provenance", uri)
                param("day", "2026-10-14")
                header("Authorization", "Bearer $adminToken")
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.lines[*].day") { value(contains("2026-10-14")) }
            }
    }
}
