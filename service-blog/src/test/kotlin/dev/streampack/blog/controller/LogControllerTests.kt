/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.core.entity.ChannelControlOptions
import dev.streampack.core.entity.User
import dev.streampack.core.model.Role
import dev.streampack.core.repository.ChannelControlOptionsRepository
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.JwtService
import dev.streampack.core.service.MessageLogService
import dev.streampack.test.TestChannelConfiguration
import java.time.Instant
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(TestChannelConfiguration::class)
class LogControllerTests {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var optionsRepository: ChannelControlOptionsRepository
    @Autowired lateinit var messageLogService: MessageLogService

    private lateinit var adminToken: String
    private lateinit var userToken: String
    private val visibleProv = "irc://libera/%23visible"
    private val hiddenProv = "irc://libera/%23hidden"

    @BeforeEach
    fun setUp() {
        val admin =
            userRepository.save(
                User(
                    username = "logsadmin",
                    email = "logsadmin@test.com",
                    displayName = "Logs Admin",
                    emailVerified = true,
                    role = Role.ADMIN,
                )
            )
        adminToken = jwtService.generateToken(admin.toUserPrincipal())

        val regular =
            userRepository.save(
                User(
                    username = "logsuser",
                    email = "logsuser@test.com",
                    displayName = "Logs User",
                    emailVerified = true,
                    role = Role.USER,
                )
            )
        userToken = jwtService.generateToken(regular.toUserPrincipal())

        optionsRepository.save(
            ChannelControlOptions(
                provenanceUri = visibleProv,
                visible = true,
                logged = true,
                active = true,
            )
        )
        optionsRepository.save(
            ChannelControlOptions(
                provenanceUri = hiddenProv,
                visible = false,
                logged = true,
                active = true,
            )
        )

        messageLogService.logInbound(visibleProv, "alice", "Visible hello")
        messageLogService.logInbound(hiddenProv, "bob", "Hidden hello")

        // DM-like context should never be listed as channel provenance
        optionsRepository.save(
            ChannelControlOptions(
                provenanceUri = "irc://libera/alice",
                visible = true,
                logged = true,
                active = true,
            )
        )
        messageLogService.logInbound("irc://libera/alice", "alice", "DM hello")
    }

    @Test
    fun `unauthenticated provenance list excludes hidden channels`() {
        mockMvc.get("/logs/provenances").andExpect {
            status { isOk() }
            jsonPath("$.provenances[*].provenanceUri") {
                value(org.hamcrest.Matchers.hasItem(visibleProv))
            }
            jsonPath("$.provenances[*].provenanceUri") {
                value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(hiddenProv)))
            }
            jsonPath("$.provenances[*].provenanceUri") {
                value(
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("irc://libera/alice"))
                )
            }
        }
    }

    @Test
    fun `admin provenance list includes hidden channels`() {
        mockMvc
            .get("/logs/provenances") { header("Authorization", "Bearer $adminToken") }
            .andExpect {
                status { isOk() }
                jsonPath("$.provenances[*].provenanceUri") {
                    value(org.hamcrest.Matchers.hasItem(hiddenProv))
                }
            }
    }

    @Test
    fun `non-admin cannot fetch hidden provenance logs`() {
        mockMvc
            .get("/logs?provenance=$hiddenProv&day=2026-03-10") {
                header("Authorization", "Bearer $userToken")
            }
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `admin can fetch hidden provenance logs`() {
        val today = Instant.now().toString().substring(0, 10)
        mockMvc
            .get("/logs?provenance=$hiddenProv&day=$today") {
                header("Authorization", "Bearer $adminToken")
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.provenanceUri") { value(hiddenProv) }
                jsonPath("$.entries") { isArray() }
            }
    }
}
