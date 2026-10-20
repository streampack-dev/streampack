/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.service

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
import dev.streampack.github.DefaultInstanceEndpoint
import dev.streampack.github.entity.GitHubRepo
import dev.streampack.github.entity.GitHubSubscription
import dev.streampack.github.repository.GitHubInstanceRepository
import dev.streampack.github.repository.GitHubRepoRepository
import dev.streampack.github.repository.GitHubSubscriptionRepository
import dev.streampack.test.ResetDatabaseBeforeEach
import dev.streampack.test.TestSecurityConfiguration
import java.net.InetSocketAddress
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
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
 * Merged and closed pull requests, and closed issues, on GitHub (#267): chosen with selectors,
 * found by polling and by webhooks, and never sent to a subscription that didn't ask for them.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ResetDatabaseBeforeEach
@Import(TestSecurityConfiguration::class)
@ResourceLock("github-api-endpoint")
class GitHubMergesAndClosesTests {

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
    @Autowired lateinit var pollingService: GitHubPollingService
    @Autowired lateinit var repoRepository: GitHubRepoRepository
    @Autowired lateinit var instanceRepository: GitHubInstanceRepository
    @Autowired lateinit var subscriptionRepository: GitHubSubscriptionRepository
    @Autowired lateinit var store: GitHubForgeStore
    @Autowired lateinit var cipher: WebhookSecretCipher
    @Autowired lateinit var capturing: Capturing

    private lateinit var httpServer: HttpServer
    private lateinit var endpoint: DefaultInstanceEndpoint
    private val queries = CopyOnWriteArrayList<String>()
    private val secret = "hook-secret"
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

    private fun reply(text: String): String {
        val message =
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
        return when (val result = eventGateway.process(message)) {
            is OperationResult.Success -> result.payload.toString()
            is OperationResult.Error -> result.message
            else -> error("no reply to '$text': $result")
        }
    }

    private fun ago(minutes: Long): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.now().minusSeconds(minutes * 60))

    /** [path] answers [closed] for `state=closed` and `[]` for anything else */
    private fun stub(path: String, closed: String = "[]") {
        try {
            httpServer.removeContext(path)
        } catch (_: IllegalArgumentException) {
            /* first registration */
        }
        httpServer.createContext(path) { exchange ->
            val query = exchange.requestURI.rawQuery.orEmpty()
            queries += "${exchange.requestURI.path}?$query"
            val body =
                when {
                    exchange.requestURI.path != path -> "[]"
                    query.contains("state=closed") -> closed
                    path == "/repos/owner/repo" ->
                        """{"id": 1, "full_name": "owner/repo", "default_branch": "main"}"""
                    else -> "[]"
                }
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
        endpoint = DefaultInstanceEndpoint(instanceRepository, store)
        endpoint.pointAt("http://localhost:${httpServer.address.port}")
        stub("/repos/owner/repo")
        for (sub in listOf("issues", "pulls", "releases")) stub("/repos/owner/repo/$sub")
    }

    @AfterEach
    fun tearDown() {
        httpServer.stop(0)
        endpoint.restore()
    }

    private fun seedRepo(mode: DeliveryMode = DeliveryMode.POLLING): GitHubRepo =
        repoRepository.save(
            GitHubRepo(
                instance = store.defaultInstance(),
                owner = "owner",
                name = "repo",
                highestIssueNumber = 50,
                highestPrNumber = 50,
                lastPolledAt = Instant.now().minusSeconds(3600),
                deliveryMode = mode,
                webhookSecret = if (mode == DeliveryMode.WEBHOOK) cipher.encrypt(secret) else null,
            ),
        )

    private fun subscribe(repo: GitHubRepo, destination: String, events: List<String>) =
        subscriptionRepository.save(
            GitHubSubscription(repo = repo, destinationUri = destination, events = events),
        )

    @Test
    fun `polling reports merges and closes in the poll window to the subscriptions that asked`() {
        val repo = seedRepo()
        subscribe(repo, channel, listOf("change_requests:merged", "issues:closed"))
        subscribe(repo, other, SubscriptionEvents.BASE)
        stub(
            "/repos/owner/repo/issues",
            """[{"number": 5, "title": "Broken", "html_url": "https://github.com/owner/repo/issues/5", "closed_at": "${ago(30)}"},
                {"number": 4, "title": "Old", "html_url": "https://github.com/owner/repo/issues/4", "closed_at": "${ago(120)}"},
                {"number": 9, "title": "Fix it", "html_url": "https://github.com/owner/repo/pull/9", "closed_at": "${ago(10)}", "pull_request": {}}]""",
        )
        stub(
            "/repos/owner/repo/pulls",
            """[{"number": 9, "title": "Fix it", "html_url": "https://github.com/owner/repo/pull/9", "closed_at": "${ago(10)}", "merged_at": "${ago(10)}"},
                {"number": 8, "title": "Abandoned", "html_url": "https://github.com/owner/repo/pull/8", "closed_at": "${ago(20)}", "merged_at": null},
                {"number": 7, "title": "Long ago", "html_url": "https://github.com/owner/repo/pull/7", "closed_at": "${ago(600)}", "merged_at": "${ago(600)}"}]""",
        )

        pollingService.pollRepo(repo.id.toString())

        assertEquals(
            setOf(
                "[owner/repo] Closed issue #5: Broken - https://github.com/owner/repo/issues/5" to
                    channel,
                "[owner/repo] Merged PR #9: Fix it - https://github.com/owner/repo/pull/9" to
                    channel,
            ),
            capturing.captured.toSet(),
        )
    }

    @Test
    fun `a pull request closed without merging is reported only where asked`() {
        val repo = seedRepo()
        subscribe(repo, channel, listOf("change_requests:closed"))
        stub(
            "/repos/owner/repo/pulls",
            """[{"number": 9, "title": "Fix it", "html_url": "u9", "closed_at": "${ago(10)}", "merged_at": "${ago(10)}"},
                {"number": 8, "title": "Abandoned", "html_url": "u8", "closed_at": "${ago(20)}", "merged_at": null}]""",
        )

        pollingService.pollRepo(repo.id.toString())
        pollingService.pollRepo(repo.id.toString())

        assertEquals(
            listOf("[owner/repo] Closed PR #8: Abandoned - u8"),
            capturing.captured.map { it.first },
        )
    }

    @Test
    fun `repositories nobody asked about merges or closes for cost no extra API calls`() {
        val repo = seedRepo()
        subscribe(repo, channel, SubscriptionEvents.BASE)

        pollingService.pollRepo(repo.id.toString())

        assertTrue(queries.none { it.contains("state=closed") }, queries.toString())
    }

    @Test
    fun `pull request webhooks report merges and closes, and issue webhooks closes`() {
        val repo = seedRepo(DeliveryMode.WEBHOOK)
        subscribe(
            repo,
            channel,
            listOf("change_requests:merged", "change_requests:closed", "issues:closed"),
        )
        subscribe(repo, other, SubscriptionEvents.BASE)

        var delivery = 0
        fun hook(event: String, payload: String) {
            mockMvc
                .post("/webhooks/github") {
                    contentType = MediaType.APPLICATION_JSON
                    content = payload
                    header("X-GitHub-Event", event)
                    header("X-GitHub-Delivery", "delivery-${++delivery}")
                    header("X-Hub-Signature-256", sign(payload.toByteArray()))
                }
                .andExpect { status { isAccepted() } }
        }
        val repository = """"repository": {"full_name": "owner/repo"}"""
        hook(
            "pull_request",
            """{"action": "closed", "pull_request": {"number": 9, "title": "T9", "html_url": "u9", "merged": true}, $repository}""",
        )
        hook(
            "pull_request",
            """{"action": "closed", "pull_request": {"number": 8, "title": "T8", "html_url": "u8", "merged": false}, $repository}""",
        )
        hook(
            "issues",
            """{"action": "closed", "issue": {"number": 5, "title": "T5", "html_url": "u5"}, $repository}""",
        )
        hook(
            "pull_request",
            """{"action": "edited", "pull_request": {"number": 7, "title": "T7", "html_url": "u7"}, $repository}""",
        )

        assertEquals(
            listOf(
                "[owner/repo] Merged PR #9: T9 - u9" to channel,
                "[owner/repo] Closed PR #8: T8 - u8" to channel,
                "[owner/repo] Closed issue #5: T5 - u5" to channel,
            ),
            capturing.captured.toList(),
        )
    }

    @Test
    fun `subscribe takes selectors, and subscriptions shows them in GitHub's words`() {
        seedRepo()
        assertEquals(
            "Subscribed to owner/repo [prs:merged releases]",
            reply("github subscribe owner/repo prs:merged releases"),
        )
        assertEquals("owner/repo [prs:merged releases]", reply("github subscriptions"))
        assertEquals(
            "Updated owner/repo filters to [prs:merged prs:closed]",
            reply("github subscribe owner/repo mrs:merged mrs:closed"),
        )
    }

    @Test
    fun `an unknown selector is named, with GitHub's selectors`() {
        seedRepo()
        val reply = reply("github subscribe owner/repo prs:reviewed")
        assertTrue(
            reply.startsWith(
                "Unknown selector 'prs:reviewed'. Selectors: issues, issues:closed, prs, prs:merged, prs:closed, releases",
            ),
            reply,
        )
    }

    private fun sign(body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return "sha256=" + mac.doFinal(body).joinToString("") { "%02x".format(it) }
    }
}
