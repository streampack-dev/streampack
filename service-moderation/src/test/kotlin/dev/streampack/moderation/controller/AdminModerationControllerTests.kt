/* Joseph B. Ottinger (C)2026 */
package dev.streampack.moderation.controller

import dev.streampack.core.entity.MessageLog
import dev.streampack.core.entity.User
import dev.streampack.core.model.Role
import dev.streampack.core.repository.MessageLogRepository
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.JwtService
import dev.streampack.core.service.MessageLogService
import dev.streampack.moderation.entity.ModerationReport
import dev.streampack.moderation.model.ModerationActionType
import dev.streampack.moderation.model.ReportStatus
import dev.streampack.moderation.repository.ModerationActionRepository
import dev.streampack.moderation.repository.ModerationReportRepository
import dev.streampack.test.ResetDatabaseBeforeEach
import java.time.Instant
import java.util.UUID
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/** The Moderation window's API (#150): admins only, and every action recorded. */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ResetDatabaseBeforeEach
class AdminModerationControllerTests {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var messageLogService: MessageLogService
    @Autowired lateinit var messageLog: MessageLogRepository
    @Autowired lateinit var reports: ModerationReportRepository
    @Autowired lateinit var actions: ModerationActionRepository

    private val uri = "irc://libera/%23java"
    private lateinit var adminToken: String
    private lateinit var userToken: String
    private lateinit var lines: List<MessageLog>
    private lateinit var report: ModerationReport

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

    @BeforeEach
    fun setUp() {
        adminToken = token("keeper", Role.ADMIN)
        userToken = token("reader", Role.USER)
        val before = Instant.now().minusSeconds(1)
        messageLogService.logInbound(uri, "bob", "anyone tried the release?")
        messageLogService.logInbound(uri, "troll", "bob: you're a fucking idiot")
        messageLogService.logInbound(uri, "bob", "excuse me?")
        messageLogService.logInbound(uri, "troll", "shut up bob, you moron")
        lines = messageLog.findWindowForModeration(uri, before, Instant.now().plusSeconds(1), 100)
        report =
            reports.save(
                ModerationReport(
                    provenanceUri = uri,
                    protocol = "irc",
                    serviceId = "libera",
                    sender = "troll",
                    score = 15.8,
                    signals = mapOf("AIMED_HOSTILITY" to 2),
                    verdictAbusive = true,
                    verdictReason = "Insults bob repeatedly.",
                    verdictModel = "claude-haiku-4-5-20251001",
                    excerptLineIds = lines.map { it.id.toString() },
                    flaggedLineIds = listOf(lines[1].id.toString(), lines[3].id.toString()),
                    citedLineIds = listOf(lines[3].id.toString()),
                    windowStart = lines.first().timestamp,
                    windowEnd = lines.last().timestamp,
                )
            )
    }

    private fun post(path: String, body: String?, token: String? = adminToken) =
        mockMvc.post(path) {
            if (token != null) header("Authorization", "Bearer $token")
            if (body != null) {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }
        }

    private fun ids(vararg line: MessageLog) =
        line.joinToString(",", "{\"lineIds\": [", "]}") { "\"${it.id}\"" }

    private fun publicContents(): List<String> =
        messageLogService
            .findMessages(uri, Instant.now().minusSeconds(60), Instant.now().plusSeconds(1), 100)
            .map { it.content }

    @Test
    fun `no one but an admin gets in`() {
        val id = report.id
        mockMvc.get("/admin/moderation/reports").andExpect { status { isUnauthorized() } }
        for ((path, body) in
            listOf(
                "/admin/moderation/reports/$id/hide" to ids(lines[1]),
                "/admin/moderation/reports/$id/purge" to
                    ids(lines[1]).dropLast(1) + ",\"confirm\":true}",
                "/admin/moderation/reports/$id/dismiss" to null,
                "/admin/moderation/lines/hide" to ids(lines[1]),
            )) {
            post(path, body, token = null).andExpect { status { isUnauthorized() } }
            post(path, body, token = userToken).andExpect { status { isForbidden() } }
        }
        for (path in
            listOf(
                "/admin/moderation/reports",
                "/admin/moderation/reports/$id",
                "/admin/moderation/logs?provenance=$uri",
            )) {
            mockMvc
                .get(path) { header("Authorization", "Bearer $userToken") }
                .andExpect { status { isForbidden() } }
        }
        assertEquals(4, publicContents().size, "nothing changed")
        assertEquals(ReportStatus.OPEN, reports.findById(id).get().status)
        assertEquals(0, actions.count())
    }

    @Test
    fun `reports list open first, with the open count`() {
        reports.save(report.copy(id = UUID(0, 0), status = ReportStatus.DISMISSED))
        val newer = reports.save(report.copy(id = UUID(0, 0), createdAt = Instant.now()))
        mockMvc
            .get("/admin/moderation/reports") { header("Authorization", "Bearer $adminToken") }
            .andExpect {
                status { isOk() }
                jsonPath("$.totalCount") { value(3) }
                jsonPath("$.openCount") { value(2) }
                jsonPath("$.reports[0].id") { value(newer.id.toString()) }
                jsonPath("$.reports[1].id") { value(report.id.toString()) }
                jsonPath("$.reports[2].status") { value("DISMISSED") }
                jsonPath("$.reports[1].sender") { value("troll") }
                jsonPath("$.reports[1].channel") { value("#java") }
                jsonPath("$.reports[1].verdict.abusive") { value(true) }
                jsonPath("$.reports[1].signals.AIMED_HOSTILITY") { value(2) }
            }
        mockMvc
            .get("/admin/moderation/reports?status=dismissed") {
                header("Authorization", "Bearer $adminToken")
            }
            .andExpect { jsonPath("$.totalCount") { value(1) } }
    }

    @Test
    fun `a report opens with its excerpt, flagged and cited lines marked`() {
        mockMvc
            .get("/admin/moderation/reports/${report.id}") {
                header("Authorization", "Bearer $adminToken")
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.report.sender") { value("troll") }
                jsonPath("$.lines.length()") { value(4) }
                jsonPath("$.lines[0].content") { value("anyone tried the release?") }
                jsonPath("$.lines[0].flagged") { value(false) }
                jsonPath("$.lines[1].flagged") { value(true) }
                jsonPath("$.lines[3].cited") { value(true) }
                jsonPath("$.lines[1].hidden") { value(false) }
                jsonPath("$.purgedLineIds.length()") { value(0) }
            }
        mockMvc
            .get("/admin/moderation/reports/${UUID.randomUUID()}") {
                header("Authorization", "Bearer $adminToken")
            }
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `hiding takes lines out of public view, and the admin still sees them, marked`() {
        post("/admin/moderation/reports/${report.id}/hide", ids(lines[1], lines[3])).andExpect {
            status { isOk() }
            jsonPath("$.changed") { value(2) }
            jsonPath("$.action.action") { value("HIDE") }
            jsonPath("$.action.actor") { value("keeper") }
        }

        assertEquals(listOf("anyone tried the release?", "excuse me?"), publicContents())
        val hidden = reports.findById(report.id).get()
        assertEquals(ReportStatus.ACTIONED, hidden.status)
        assertEquals("keeper", hidden.actedBy)
        assertNotNull(hidden.actedAt)
        val action = actions.findByReportIdOrderByActedAtAsc(report.id).single()
        assertEquals(ModerationActionType.HIDE, action.action)
        assertEquals("keeper", action.actor)
        assertNotNull(action.actorId)

        mockMvc
            .get("/admin/moderation/reports/${report.id}") {
                header("Authorization", "Bearer $adminToken")
            }
            .andExpect {
                jsonPath("$.lines.length()") { value(4) }
                jsonPath("$.lines[1].hidden") { value(true) }
                jsonPath("$.lines[3].hidden") { value(true) }
                jsonPath("$.actions[0].action") { value("HIDE") }
            }
        mockMvc
            .get("/admin/moderation/logs") {
                param("provenance", uri)
                header("Authorization", "Bearer $adminToken")
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.lines[*].hidden") { value(contains(false, true, false, true)) }
            }

        post("/admin/moderation/reports/${report.id}/unhide", ids(lines[1])).andExpect {
            status { isOk() }
            jsonPath("$.changed") { value(1) }
        }
        assertEquals(3, publicContents().size)
        assertEquals(2, actions.findByReportIdOrderByActedAtAsc(report.id).size)
    }

    @Test
    fun `only the report's own lines can be acted on from it`() {
        messageLogService.logInbound(uri, "carol", "an unrelated line")
        val other =
            messageLog
                .findWindowForModeration(
                    uri,
                    Instant.now().minusSeconds(60),
                    Instant.now().plusSeconds(1),
                    100,
                )
                .single { it.content == "an unrelated line" }
        post("/admin/moderation/reports/${report.id}/hide", ids(other)).andExpect {
            status { isBadRequest() }
        }
        post("/admin/moderation/reports/${report.id}/hide", "{\"lineIds\": []}").andExpect {
            status { isBadRequest() }
        }
        assertEquals(5, publicContents().size)
    }

    @Test
    fun `purging needs confirming, and can't be undone`() {
        post("/admin/moderation/reports/${report.id}/purge", ids(lines[1])).andExpect {
            status { isBadRequest() }
        }
        assertEquals(4, publicContents().size)

        post(
                "/admin/moderation/reports/${report.id}/purge",
                ids(lines[1]).dropLast(1) + ", \"confirm\": true, \"note\": \"illegal\"}",
            )
            .andExpect {
                status { isOk() }
                jsonPath("$.changed") { value(1) }
                jsonPath("$.action.note") { value("illegal") }
            }
        assertTrue(messageLog.findForModeration(listOf(lines[1].id)).isEmpty())
        mockMvc
            .get("/admin/moderation/reports/${report.id}") {
                header("Authorization", "Bearer $adminToken")
            }
            .andExpect {
                jsonPath("$.report.status") { value("ACTIONED") }
                jsonPath("$.lines.length()") { value(3) }
                jsonPath("$.purgedLineIds") { value(contains(lines[1].id.toString())) }
                jsonPath("$.actions[0].action") { value("PURGE") }
                jsonPath("$.actions[0].lineIds") { value(contains(lines[1].id.toString())) }
            }
    }

    @Test
    fun `dismissing closes an open report and is recorded`() {
        post("/admin/moderation/reports/${report.id}/dismiss", "{\"note\": \"banter\"}").andExpect {
            status { isOk() }
            jsonPath("$.action.action") { value("DISMISS") }
            jsonPath("$.action.actor") { value("keeper") }
        }
        val dismissed = reports.findById(report.id).get()
        assertEquals(ReportStatus.DISMISSED, dismissed.status)
        assertEquals("keeper", dismissed.actedBy)
        assertNotNull(dismissed.actedAt)
        assertEquals(4, publicContents().size, "dismissing hides nothing")

        post("/admin/moderation/reports/${report.id}/dismiss", null).andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `lines can be hidden and unhidden from a log day`() {
        post("/admin/moderation/lines/hide", ids(lines[3])).andExpect {
            status { isOk() }
            jsonPath("$.changed") { value(1) }
            jsonPath("$.action.reportId") { value(null as Any?) }
        }
        assertTrue("shut up bob, you moron" !in publicContents())
        post("/admin/moderation/lines/unhide", ids(lines[3])).andExpect {
            status { isOk() }
            jsonPath("$.changed") { value(1) }
        }
        assertTrue("shut up bob, you moron" in publicContents())
        post("/admin/moderation/lines/hide", "{\"lineIds\": [\"${UUID.randomUUID()}\"]}")
            .andExpect { status { isBadRequest() } }
        mockMvc
            .get("/admin/moderation/logs") {
                param("provenance", uri)
                param("day", "not-a-day")
                header("Authorization", "Bearer $adminToken")
            }
            .andExpect { status { isBadRequest() } }
    }

    @Test
    fun `a direct line is never read or touched`() {
        messageLogService.logInbound(uri, "troll", "said directly", direct = true)
        mockMvc
            .get("/admin/moderation/logs") {
                param("provenance", uri)
                header("Authorization", "Bearer $adminToken")
            }
            .andExpect {
                jsonPath("$.lines.length()") { value(4) }
                jsonPath("$.lines[*].content") { value(not(hasItem("said directly"))) }
            }
    }
}
