/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.core.entity.ChannelControlOptions
import dev.streampack.core.entity.User
import dev.streampack.core.model.MessageKind
import dev.streampack.core.model.Role
import dev.streampack.core.repository.ChannelControlOptionsRepository
import dev.streampack.core.repository.MessageLogRepository
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.JwtService
import dev.streampack.core.service.MessageLogService
import dev.streampack.core.service.ThrottleService
import dev.streampack.test.ResetDatabaseBeforeEach
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

@SpringBootTest
@AutoConfigureMockMvc
@ResetDatabaseBeforeEach
@Import(TestChannelConfiguration::class)
class LogControllerTests {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var optionsRepository: ChannelControlOptionsRepository
    @Autowired lateinit var messageLogService: MessageLogService
    @Autowired lateinit var messageLogRepository: MessageLogRepository
    @Autowired lateinit var throttleService: ThrottleService

    private lateinit var adminToken: String
    private lateinit var userToken: String
    private val visibleProv = "irc://libera/%23visible"
    private val hiddenProv = "irc://libera/%23hidden"

    @BeforeEach
    fun setUp() {
        throttleService.clear()
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

    @Test
    fun `a line hidden by moderation is gone from the day and search, for admins too`() {
        messageLogService.logInbound(visibleProv, "troll", "an abusive remark")
        val today = Instant.now().toString().substring(0, 10)
        val line =
            messageLogRepository
                .findWindowForModeration(
                    visibleProv,
                    Instant.now().minusSeconds(60),
                    Instant.now().plusSeconds(1),
                    100,
                )
                .single { it.content == "an abusive remark" }
        messageLogRepository.setHiddenForModeration(listOf(line.id), true)

        for (token in listOf(null, userToken, adminToken)) {
            mockMvc
                .get("/logs?provenance=$visibleProv&day=$today") {
                    if (token != null) header("Authorization", "Bearer $token")
                }
                .andExpect {
                    status { isOk() }
                    jsonPath("$.entries[*].content") {
                        value(org.hamcrest.Matchers.contains("Visible hello"))
                    }
                }
            search(visibleProv, "abusive", token = token).andExpect {
                status { isOk() }
                jsonPath("$.totalCount") { value(0) }
            }
            search(visibleProv, null, token = token, sender = "troll").andExpect {
                status { isOk() }
                jsonPath("$.totalCount") { value(0) }
            }
        }
        mockMvc.get("/logs/provenances").andExpect {
            jsonPath("$.provenances[*].latestSender") {
                value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("troll")))
            }
        }
    }

    // -- Search (#101) --

    private fun search(
        provenance: String,
        q: String?,
        token: String? = null,
        page: Int? = null,
        size: Int? = null,
        sender: String? = null,
    ) =
        mockMvc.get("/logs/search") {
            param("provenance", provenance)
            if (q != null) param("q", q)
            if (sender != null) param("sender", sender)
            if (page != null) param("page", page.toString())
            if (size != null) param("size", size.toString())
            if (token != null) header("Authorization", "Bearer $token")
        }

    @Test
    fun `search finds a channel's lines containing the text, ignoring case, newest first`() {
        messageLogService.logInbound(visibleProv, "carol", "Graal native images")
        messageLogService.logInbound(visibleProv, "dave", "graalvm is fast")
        messageLogService.logInbound(hiddenProv, "bob", "graal in secret")
        val today = Instant.now().toString().substring(0, 10)

        search(visibleProv, "GRAAL").andExpect {
            status { isOk() }
            jsonPath("$.provenanceUri") { value(visibleProv) }
            jsonPath("$.totalCount") { value(2) }
            jsonPath("$.hits.length()") { value(2) }
            jsonPath("$.hits[0].content") { value("graalvm is fast") }
            jsonPath("$.hits[0].sender") { value("dave") }
            jsonPath("$.hits[0].day") { value(today) }
            jsonPath("$.hits[1].content") { value("Graal native images") }
        }
    }

    @Test
    fun `search of a hidden channel is a 404 for readers and anonymous callers, and works for admins`() {
        search(hiddenProv, "hello").andExpect { status { isNotFound() } }
        search(hiddenProv, "hello", token = userToken).andExpect { status { isNotFound() } }
        search(hiddenProv, "hello", token = adminToken).andExpect {
            status { isOk() }
            jsonPath("$.hits[0].content") { value("Hidden hello") }
        }
        search("irc://libera/alice", "hello").andExpect { status { isNotFound() } }
    }

    @Test
    fun `search wants three to two hundred characters, and a sane page`() {
        search(visibleProv, null).andExpect { status { isBadRequest() } }
        search(visibleProv, " ab ").andExpect { status { isBadRequest() } }
        search(visibleProv, "x".repeat(201)).andExpect { status { isBadRequest() } }
        search(visibleProv, "hello", page = -1).andExpect { status { isBadRequest() } }
        search(visibleProv, "hello", size = 0).andExpect { status { isBadRequest() } }
        search(visibleProv, "hello", size = 101).andExpect { status { isBadRequest() } }
    }

    @Test
    fun `search pages through its results`() {
        repeat(5) { messageLogService.logInbound(visibleProv, "erin", "page item $it") }

        search(visibleProv, "page item", page = 1, size = 2).andExpect {
            status { isOk() }
            jsonPath("$.totalCount") { value(5) }
            jsonPath("$.totalPages") { value(3) }
            jsonPath("$.page") { value(1) }
            jsonPath("$.hits.length()") { value(2) }
            jsonPath("$.hits[0].content") { value("page item 2") }
        }
    }

    @Test
    fun `search matches wildcard characters as written`() {
        messageLogService.logInbound(visibleProv, "frank", "100% sure")
        messageLogService.logInbound(visibleProv, "frank", "1000 sure")
        messageLogService.logInbound(visibleProv, "frank", "a_b test")
        messageLogService.logInbound(visibleProv, "frank", "axb test")

        search(visibleProv, "0% s").andExpect {
            jsonPath("$.totalCount") { value(1) }
            jsonPath("$.hits[0].content") { value("100% sure") }
        }
        search(visibleProv, "a_b").andExpect {
            jsonPath("$.totalCount") { value(1) }
            jsonPath("$.hits[0].content") { value("a_b test") }
        }
    }

    @Test
    fun `search is limited for a signed-in caller`() {
        repeat(30) {
            search(visibleProv, "hello", token = userToken).andExpect { status { isOk() } }
        }
        search(visibleProv, "hello", token = userToken).andExpect { status { isTooManyRequests() } }
        // Another caller has their own allowance.
        search(visibleProv, "hello", token = adminToken).andExpect { status { isOk() } }
    }

    @Test
    fun `search narrows to what one person said, or lists it all`() {
        messageLogService.logInbound(visibleProv, "dreamreal", "the rover project is late")
        messageLogService.logInbound(visibleProv, "grace", "rover, rover, send the rover over")
        messageLogService.logInbound(visibleProv, "dreamreal", "lunch?")

        search(visibleProv, "rover", sender = "DreamReal").andExpect {
            status { isOk() }
            jsonPath("$.sender") { value("DreamReal") }
            jsonPath("$.totalCount") { value(1) }
            jsonPath("$.hits[0].content") { value("the rover project is late") }
        }
        search(visibleProv, null, sender = "dreamreal").andExpect {
            status { isOk() }
            jsonPath("$.totalCount") { value(2) }
            jsonPath("$.hits[0].content") { value("lunch?") }
            jsonPath("$.hits[1].content") { value("the rover project is late") }
        }
        search(visibleProv, null, sender = "  ").andExpect { status { isBadRequest() } }
        search(visibleProv, null, sender = "x".repeat(256)).andExpect { status { isBadRequest() } }
    }

    @Test
    fun `each listed channel carries its readable path`() {
        mockMvc.get("/logs/provenances").andExpect {
            status { isOk() }
            jsonPath("$.provenances[*].path") {
                value(org.hamcrest.Matchers.hasItem("irc/libera/visible"))
            }
        }
    }

    @Test
    fun `a channel is found by its readable address`() {
        for (name in listOf("visible", "%23visible")) {
            // As a browser sends it: %23 stays %23, not encoded again
            mockMvc.get(java.net.URI.create("/logs/channels/irc/libera/$name")).andExpect {
                status { isOk() }
                jsonPath("$.provenanceUri") { value(visibleProv) }
                jsonPath("$.path") { value("irc/libera/visible") }
            }
        }
    }

    @Test
    fun `a hidden channel's address is not found, except by an admin`() {
        mockMvc
            .get("/logs/channels/irc/libera/hidden") {
                header("Authorization", "Bearer $userToken")
            }
            .andExpect { status { isNotFound() } }
        mockMvc
            .get("/logs/channels/irc/libera/hidden") {
                header("Authorization", "Bearer $adminToken")
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.provenanceUri") { value(hiddenProv) }
            }
    }

    @Test
    fun `an address that names no channel is not found, nor a private conversation`() {
        mockMvc.get("/logs/channels/irc/libera/nowhere").andExpect { status { isNotFound() } }
        mockMvc.get("/logs/channels/irc/libera/alice").andExpect { status { isNotFound() } }
    }

    // -- Kinds and events (#174) --

    private fun logEvents() {
        messageLogService.logInbound(
            visibleProv,
            "ada",
            "* ada joined #visible",
            kind = MessageKind.JOIN,
        )
        messageLogService.logInbound(visibleProv, "ada", "ada visible remark")
        messageLogService.logInbound(
            visibleProv,
            "ada",
            "* ada quit (visible exit)",
            kind = MessageKind.QUIT,
        )
        messageLogService.logInbound(visibleProv, "ada", "a direct visible line", direct = true)
        messageLogService.logInbound(
            visibleProv,
            "ada",
            "* ada quit (direct visible)",
            direct = true,
            kind = MessageKind.QUIT,
        )
    }

    private fun day(vararg params: Pair<String, String>) =
        mockMvc.get("/logs") {
            param("provenance", visibleProv)
            param("day", Instant.now().toString().substring(0, 10))
            params.forEach { (k, v) -> param(k, v) }
        }

    @Test
    fun `each day line carries its kind, and every kind by default`() {
        logEvents()
        for (call in listOf(day(), day("events" to "all"), day("events" to "ALL"))) {
            call.andExpect {
                status { isOk() }
                jsonPath("$.entries[*].content") {
                    value(
                        org.hamcrest.Matchers.contains(
                            "Visible hello",
                            "* ada joined #visible",
                            "ada visible remark",
                            "* ada quit (visible exit)",
                        )
                    )
                }
                jsonPath("$.entries[*].kind") {
                    value(org.hamcrest.Matchers.contains("MESSAGE", "JOIN", "MESSAGE", "QUIT"))
                }
            }
        }
    }

    @Test
    fun `events=none leaves only what was said, and kinds picks kinds`() {
        logEvents()
        day("events" to "none").andExpect {
            status { isOk() }
            jsonPath("$.entries[*].content") {
                value(org.hamcrest.Matchers.contains("Visible hello", "ada visible remark"))
            }
        }
        day("kinds" to "quit, Join").andExpect {
            status { isOk() }
            jsonPath("$.entries[*].kind") { value(org.hamcrest.Matchers.contains("JOIN", "QUIT")) }
        }
        day("events" to "none", "kinds" to "QUIT").andExpect {
            status { isOk() }
            jsonPath("$.entries.length()") { value(0) }
        }
        day("events" to "some").andExpect { status { isBadRequest() } }
        day("kinds" to "JOIN,WAVE").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `search carries kinds and takes the same filters`() {
        logEvents()
        search(visibleProv, "visible").andExpect {
            status { isOk() }
            jsonPath("$.totalCount") { value(4) }
            jsonPath("$.hits[*].kind") {
                value(org.hamcrest.Matchers.contains("QUIT", "MESSAGE", "JOIN", "MESSAGE"))
            }
        }
        mockMvc
            .get("/logs/search") {
                param("provenance", visibleProv)
                param("sender", "ada")
                param("events", "none")
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.totalCount") { value(1) }
                jsonPath("$.hits[0].content") { value("ada visible remark") }
                jsonPath("$.hits[0].kind") { value("MESSAGE") }
            }
        mockMvc
            .get("/logs/search") {
                param("provenance", visibleProv)
                param("q", "quit")
                param("kinds", "QUIT")
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.totalCount") { value(1) }
                jsonPath("$.hits[0].content") { value("* ada quit (visible exit)") }
            }
        mockMvc
            .get("/logs/search") {
                param("provenance", visibleProv)
                param("q", "visible")
                param("events", "maybe")
            }
            .andExpect { status { isBadRequest() } }
    }

    @Test
    fun `direct and hidden lines stay out under every events and kinds filter`() {
        logEvents()
        val join =
            messageLogRepository
                .findWindowForModeration(
                    visibleProv,
                    Instant.now().minusSeconds(60),
                    Instant.now().plusSeconds(1),
                    100,
                )
                .single { it.kind == MessageKind.JOIN }
        messageLogRepository.setHiddenForModeration(listOf(join.id), true)

        val filters =
            listOf(
                emptyList(),
                listOf("events" to "all"),
                listOf("events" to "none"),
                listOf("kinds" to "QUIT"),
                listOf("kinds" to "JOIN"),
                listOf("kinds" to "MESSAGE,JOIN,PART,QUIT,NICK,TOPIC"),
            )
        for (filter in filters) {
            for (token in listOf(null, adminToken)) {
                mockMvc
                    .get("/logs") {
                        param("provenance", visibleProv)
                        param("day", Instant.now().toString().substring(0, 10))
                        filter.forEach { (k, v) -> param(k, v) }
                        if (token != null) header("Authorization", "Bearer $token")
                    }
                    .andExpect {
                        status { isOk() }
                        jsonPath("$.entries[*].content") {
                            value(
                                org.hamcrest.Matchers.everyItem(
                                    org.hamcrest.Matchers.not(
                                        org.hamcrest.Matchers.anyOf(
                                            org.hamcrest.Matchers.containsString("direct"),
                                            org.hamcrest.Matchers.containsString("joined"),
                                        )
                                    )
                                )
                            )
                        }
                    }
                mockMvc
                    .get("/logs/search") {
                        param("provenance", visibleProv)
                        param("sender", "ada")
                        filter.forEach { (k, v) -> param(k, v) }
                        if (token != null) header("Authorization", "Bearer $token")
                    }
                    .andExpect {
                        status { isOk() }
                        jsonPath("$.hits[*].content") {
                            value(
                                org.hamcrest.Matchers.everyItem(
                                    org.hamcrest.Matchers.not(
                                        org.hamcrest.Matchers.anyOf(
                                            org.hamcrest.Matchers.containsString("direct"),
                                            org.hamcrest.Matchers.containsString("joined"),
                                        )
                                    )
                                )
                            )
                        }
                    }
            }
        }
    }
}
