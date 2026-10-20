/* Joseph B. Ottinger (C)2026 */
package dev.streampack.gitlab.service

import com.sun.net.httpserver.HttpServer
import dev.streampack.core.integration.EgressSubscriber
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.forge.model.DeliveryMode
import dev.streampack.forge.subscription.SubscriptionEvents
import dev.streampack.gitlab.entity.GitLabProject
import dev.streampack.gitlab.entity.GitLabSubscription
import dev.streampack.gitlab.repository.GitLabInstanceRepository
import dev.streampack.gitlab.repository.GitLabProjectRepository
import dev.streampack.gitlab.repository.GitLabSubscriptionRepository
import dev.streampack.test.ResetDatabaseBeforeEach
import dev.streampack.test.TestSecurityConfiguration
import java.net.InetSocketAddress
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.messaging.SubscribableChannel
import org.springframework.messaging.support.MessageBuilder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

/**
 * Merged and closed merge requests, and closed issues, on GitLab (#267): chosen with selectors,
 * found by polling and by hooks, and never sent to a subscription that didn't ask for them.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ResetDatabaseBeforeEach
@Import(TestSecurityConfiguration::class)
@ResourceLock("gitlab-api-endpoint")
class GitLabMergesAndClosesTests {

    @TestConfiguration
    class CapturingConfig {
        @Bean
        fun capturingClosureSubscriber(
            @Qualifier("egressChannel") egressChannel: SubscribableChannel,
        ): Capturing {
            val subscriber = Capturing()
            egressChannel.subscribe(subscriber)
            return subscriber
        }
    }

    class Capturing : EgressSubscriber() {
        val captured = CopyOnWriteArrayList<Pair<String, String>>()

        override fun matches(provenance: Provenance): Boolean = true

        override fun deliver(result: OperationResult, provenance: Provenance) {
            if (result !is OperationResult.Success) return
            captured.add(result.payload.toString() to provenance.encode())
        }
    }

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var pollingService: GitLabPollingService
    @Autowired lateinit var projectRepository: GitLabProjectRepository
    @Autowired lateinit var instanceRepository: GitLabInstanceRepository
    @Autowired lateinit var subscriptionRepository: GitLabSubscriptionRepository
    @Autowired lateinit var store: GitLabForgeStore
    @Autowired lateinit var cipher: GitLabWebhookSecretCipher
    @Autowired lateinit var capturing: Capturing

    private lateinit var httpServer: HttpServer
    private val queries = CopyOnWriteArrayList<String>()
    private val secret = "hook-token"
    private val channel =
        Provenance(protocol = Protocol.IRC, serviceId = "libera", replyTo = "#dev").encode()
    private val other = Provenance(protocol = Protocol.CONSOLE, replyTo = "local").encode()
    private val adminUser =
        UserPrincipal(
            id = UUID.randomUUID(),
            username = "admin",
            displayName = "Admin",
            role = Role.ADMIN,
        )

    private fun adminMessage(text: String) =
        MessageBuilder.withPayload(text)
            .setHeader(
                Provenance.HEADER,
                Provenance(
                    protocol = Protocol.IRC,
                    serviceId = "libera",
                    replyTo = "#dev",
                    user = adminUser,
                ),
            )
            .build()

    private fun reply(text: String): String =
        when (val result = eventGateway.process(adminMessage(text))) {
            is OperationResult.Success -> result.payload.toString()
            is OperationResult.Error -> result.message
            else -> error("no reply to '$text': $result")
        }

    private fun ago(minutes: Long): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.now().minusSeconds(minutes * 60))

    /** Each resource answers by its `state` query: [byState] for a state, `[]` otherwise */
    private fun stub(path: String, byState: Map<String, String> = emptyMap()) {
        httpServer.createContext(path) { exchange ->
            val query = exchange.requestURI.rawQuery.orEmpty()
            queries += "${exchange.requestURI.path}?$query"
            val state = Regex("(?:^|&)state=([a-z]+)").find(query)?.groupValues?.get(1)
            val body = byState[state] ?: "[]"
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
    }

    @BeforeEach
    fun setUp() {
        capturing.captured.clear()
        queries.clear()
        httpServer = HttpServer.create(InetSocketAddress(0), 0)
        httpServer.start()
        instanceRepository.save(
            store
                .defaultInstance()
                .copy(apiUrl = "http://localhost:${httpServer.address.port}/api/v4"),
        )
        stub("/api/v4/projects/group/project/releases")
    }

    @AfterEach fun tearDown() = httpServer.stop(0)

    private fun seedProject(mode: DeliveryMode = DeliveryMode.POLLING): GitLabProject =
        projectRepository.save(
            GitLabProject(
                instance = store.defaultInstance(),
                fullPath = "group/project",
                projectId = 100,
                highestIssueNumber = 10,
                highestMrNumber = 10,
                lastPolledAt = Instant.now().minusSeconds(3600),
                deliveryMode = mode,
                webhookSecret = if (mode == DeliveryMode.WEBHOOK) cipher.encrypt(secret) else null,
            ),
        )

    private fun subscribe(project: GitLabProject, destination: String, events: List<String>) =
        subscriptionRepository.save(
            GitLabSubscription(project = project, destinationUri = destination, events = events),
        )

    @Test
    fun `polling reports merges and closes in the poll window to the subscriptions that asked`() {
        val project = seedProject()
        subscribe(project, channel, listOf("change_requests:merged", "issues:closed"))
        subscribe(project, other, SubscriptionEvents.BASE)
        stub(
            "/api/v4/projects/group/project/issues",
            mapOf(
                "closed" to
                    """[{"iid": 5, "title": "Broken", "web_url": "https://gitlab.com/group/project/-/issues/5", "closed_at": "${ago(30)}"},
                        {"iid": 4, "title": "Old", "web_url": "https://gitlab.com/group/project/-/issues/4", "closed_at": "${ago(120)}"}]""",
            ),
        )
        stub(
            "/api/v4/projects/group/project/merge_requests",
            mapOf(
                "merged" to
                    """[{"iid": 9, "title": "Fix it", "web_url": "https://gitlab.com/group/project/-/merge_requests/9", "merged_at": "${ago(10)}"}]""",
                "closed" to
                    """[{"iid": 8, "title": "Abandoned", "web_url": "https://gitlab.com/group/project/-/merge_requests/8", "closed_at": "${ago(20)}"}]""",
            ),
        )

        pollingService.pollProject(project.id.toString())

        assertEquals(
            setOf(
                "[group/project] Closed issue #5: Broken - https://gitlab.com/group/project/-/issues/5" to
                    channel,
                "[group/project] Merged MR !9: Fix it - https://gitlab.com/group/project/-/merge_requests/9" to
                    channel,
            ),
            capturing.captured.toSet(),
        )
    }

    @Test
    fun `a close is reported once, however many polls see it`() {
        val project = seedProject()
        subscribe(project, channel, listOf("change_requests:merged"))
        stub(
            "/api/v4/projects/group/project/merge_requests",
            mapOf(
                "merged" to
                    """[{"iid": 9, "title": "Fix it", "web_url": "u9", "merged_at": "${ago(10)}"}]""",
            ),
        )
        stub("/api/v4/projects/group/project/issues")

        pollingService.pollProject(project.id.toString())
        pollingService.pollProject(project.id.toString())

        assertEquals(
            listOf("[group/project] Merged MR !9: Fix it - u9"),
            capturing.captured.map { it.first },
        )
    }

    @Test
    fun `projects nobody asked about merges or closes for cost no extra API calls`() {
        val project = seedProject()
        subscribe(project, channel, SubscriptionEvents.BASE + "pipelines:failed")
        stub("/api/v4/projects/group/project/issues")
        stub("/api/v4/projects/group/project/merge_requests")
        stub("/api/v4/projects/group/project/pipelines")

        pollingService.pollProject(project.id.toString())

        assertTrue(
            queries.none { it.contains("state=closed") || it.contains("state=merged") },
            queries.toString(),
        )
    }

    @Test
    fun `merge request hooks report merges and closes, and issue hooks closes`() {
        val project = seedProject(DeliveryMode.WEBHOOK)
        subscribe(
            project,
            channel,
            listOf("change_requests:merged", "change_requests:closed", "issues:closed"),
        )
        subscribe(project, other, SubscriptionEvents.BASE)

        fun hook(event: String, kind: String, iid: Int, action: String) {
            mockMvc
                .post("/webhooks/gitlab") {
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        """{"object_kind": "$kind",
                            "project": {"id": 100, "path_with_namespace": "group/project"},
                            "object_attributes": {"iid": $iid, "title": "T$iid", "url": "u$iid", "action": "$action"}}"""
                    header("X-Gitlab-Event", event)
                    header("X-Gitlab-Token", secret)
                }
                .andExpect { status { isAccepted() } }
        }
        hook("Merge Request Hook", "merge_request", 9, "merge")
        hook("Merge Request Hook", "merge_request", 8, "close")
        hook("Issue Hook", "issue", 5, "close")
        hook("Merge Request Hook", "merge_request", 7, "update")

        assertEquals(
            listOf(
                "[group/project] Merged MR !9: T9 - u9" to channel,
                "[group/project] Closed MR !8: T8 - u8" to channel,
                "[group/project] Closed issue #5: T5 - u5" to channel,
            ),
            capturing.captured.toList(),
        )
    }

    @Test
    fun `subscribe takes selectors, and subscriptions shows them in GitLab's words`() {
        seedProject()
        assertEquals(
            "Subscribed to group/project [issues:closed mrs:merged pipelines:failed]",
            reply("gitlab subscribe group/project mrs:merged issues:closed pipelines:failed"),
        )
        assertEquals(
            "group/project [issues:closed mrs:merged pipelines:failed]",
            reply("gitlab subscriptions"),
        )
        assertEquals(
            "Updated group/project filters to [mrs mrs:merged]",
            reply("gitlab subscribe group/project prs prs:merged"),
        )
        assertEquals(
            "Updated group/project filters to the defaults",
            reply("gitlab subscribe group/project issues mrs releases"),
        )
        assertEquals("group/project", reply("gitlab subscriptions"))
    }

    @Test
    fun `an unknown selector is named, with GitLab's selectors`() {
        seedProject()
        val reply = reply("gitlab subscribe group/project mrs:reviewed")
        assertTrue(
            reply.startsWith(
                "Unknown selector 'mrs:reviewed'. Selectors: issues, issues:closed, mrs, mrs:merged, mrs:closed, releases",
            ),
            reply,
        )
    }

    @Test
    fun `a subscription stored before selectors keeps getting what it got`() {
        val project = seedProject()
        subscribe(project, channel, SubscriptionEvents.BASE + "pipelines")
        assertEquals("group/project [pipelines]", reply("gitlab subscriptions"))
    }
}
