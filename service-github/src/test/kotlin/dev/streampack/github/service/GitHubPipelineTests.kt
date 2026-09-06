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
import dev.streampack.forge.model.PipelineOutcome
import dev.streampack.github.DefaultInstanceEndpoint
import dev.streampack.github.entity.GitHubRepo
import dev.streampack.github.repository.GitHubInstanceRepository
import dev.streampack.github.repository.GitHubPipelineRepository
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
import org.junit.jupiter.api.Assertions.assertInstanceOf
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
 * Pipeline outcomes on GitHub: subscribe filters, polling of workflow runs, and workflow_run hooks.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ResetDatabaseBeforeEach
@Import(TestSecurityConfiguration::class)
@ResourceLock("github-api-endpoint")
class GitHubPipelineTests {

    @TestConfiguration
    class CapturingConfig {
        @Bean
        fun capturingPipelineSubscriber(
            @Qualifier("egressChannel") egressChannel: SubscribableChannel
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
            captured.add(
                (result as OperationResult.Success).payload.toString() to provenance.encode()
            )
        }
    }

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var pollingService: GitHubPollingService
    @Autowired lateinit var subscriptionService: GitHubSubscriptionService
    @Autowired lateinit var repoRepository: GitHubRepoRepository
    @Autowired lateinit var instanceRepository: GitHubInstanceRepository
    @Autowired lateinit var subscriptionRepository: GitHubSubscriptionRepository
    @Autowired lateinit var pipelineRepository: GitHubPipelineRepository
    @Autowired lateinit var store: GitHubForgeStore
    @Autowired lateinit var cipher: WebhookSecretCipher
    @Autowired lateinit var capturing: Capturing

    private lateinit var httpServer: HttpServer
    private lateinit var endpoint: DefaultInstanceEndpoint
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

    @BeforeEach
    fun setUp() {
        capturing.captured.clear()
        httpServer = HttpServer.create(InetSocketAddress(0), 0)
        httpServer.start()
        endpoint = DefaultInstanceEndpoint(instanceRepository, store)
        endpoint.pointAt("http://localhost:${httpServer.address.port}")
        stub(
            "/repos/owner/repo",
            """{"id": 1, "full_name": "owner/repo", "default_branch": "main"}""",
        )
        for (sub in listOf("issues", "pulls", "releases")) stub("/repos/owner/repo/$sub", "[]")
    }

    @AfterEach
    fun tearDown() {
        httpServer.stop(0)
        endpoint.restore()
    }

    private fun stub(path: String, body: String) {
        try {
            httpServer.removeContext(path)
        } catch (_: IllegalArgumentException) {
            /* first registration */
        }
        httpServer.createContext(path) { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
    }

    private fun iso(instant: Instant) = DateTimeFormatter.ISO_INSTANT.format(instant)

    private fun run(
        id: Long,
        name: String,
        branch: String,
        status: String,
        conclusion: String?,
        pr: Int? = null,
        updatedAt: Instant = Instant.now(),
    ): String {
        val started = updatedAt.minusSeconds(252)
        val prs =
            if (pr == null) "[]"
            else """[{"number": $pr, "url": "https://api.github.com/repos/owner/repo/pulls/$pr"}]"""
        return """{"id": $id, "name": "$name", "head_branch": "$branch", "head_sha": "abc", "status": "$status",
            "conclusion": ${conclusion?.let { "\"$it\"" } ?: "null"}, "html_url": "https://github.com/owner/repo/actions/runs/$id",
            "run_started_at": "${iso(started)}", "created_at": "${iso(started)}", "updated_at": "${iso(updatedAt)}", "pull_requests": $prs}"""
    }

    private fun stubRuns(vararg runs: String) =
        stub(
            "/repos/owner/repo/actions/runs",
            """{"total_count": ${runs.size}, "workflow_runs": [${runs.joinToString(",")}]}""",
        )

    private fun stubJobs(runId: Long, vararg jobs: Pair<String, String>) {
        val body =
            jobs.mapIndexed { i, (name, conclusion) ->
                """{"id": ${runId * 10 + i}, "name": "$name", "status": "completed", "conclusion": "$conclusion"}"""
            }
        stub(
            "/repos/owner/repo/actions/runs/$runId/jobs",
            """{"total_count": ${jobs.size}, "jobs": [${body.joinToString(",")}]}""",
        )
    }

    private fun seedRepo(lastPolled: Instant = Instant.now().minusSeconds(3600)): GitHubRepo =
        repoRepository.save(
            GitHubRepo(
                instance = store.defaultInstance(),
                owner = "owner",
                name = "repo",
                lastPolledAt = lastPolled,
                deliveryMode = DeliveryMode.WEBHOOK,
                webhookSecret = cipher.encrypt(secret),
            )
        )

    @Test
    fun `subscribe accepts pipeline filters and rejects unknown ones`() {
        seedRepo()
        val plain = eventGateway.process(adminMessage("github subscribe owner/repo"))
        assertEquals(
            "Subscribed to owner/repo",
            assertInstanceOf(OperationResult.Success::class.java, plain).payload,
        )
        val updated =
            eventGateway.process(
                adminMessage(
                    "github subscribe owner/repo pipelines:failed pipelines:branch:develop"
                )
            )
        val text = assertInstanceOf(OperationResult.Success::class.java, updated).payload.toString()
        assertTrue(
            text.contains("pipelines:failed") && text.contains("pipelines:branch:develop"),
            text,
        )
        val stored = subscriptionRepository.findByDestinationUriAndActiveTrue(channel).single()
        assertEquals(
            listOf(
                "issues",
                "change_requests",
                "releases",
                "pipelines:failed",
                "pipelines:branch:develop",
            ),
            stored.events,
        )

        val bad =
            eventGateway.process(adminMessage("github subscribe owner/repo pipelines:sometimes"))
        assertTrue(
            assertInstanceOf(OperationResult.Error::class.java, bad)
                .message
                .contains("pipelines:sometimes")
        )

        val listing = eventGateway.process(adminMessage("github subscriptions"))
        val listed =
            assertInstanceOf(OperationResult.Success::class.java, listing).payload.toString()
        assertTrue(
            listed.contains("owner/repo [pipelines:failed pipelines:branch:develop]"),
            listed,
        )
    }

    @Test
    fun `polling reports newly settled runs to matching subscriptions only`() {
        val repo = seedRepo()
        subscriptionService.subscribe(
            store.defaultInstance(),
            "owner/repo",
            channel,
            listOf(dev.streampack.forge.subscription.PipelineFilter.parse("pipelines:failed")!!),
        )
        subscriptionService.subscribe(
            store.defaultInstance(),
            "owner/repo",
            other,
            listOf(
                dev.streampack.forge.subscription.PipelineFilter.parse("pipelines")!!,
                dev.streampack.forge.subscription.PipelineFilter.parse("pipelines:default-branch")!!,
            ),
        )
        val old = Instant.now().minusSeconds(7200)
        stubRuns(
            run(1, "CI", "feature", "completed", "failure", pr = 12),
            run(2, "CI", "feature", "completed", "success", pr = 13),
            run(3, "CI", "main", "completed", "success"),
            run(4, "CI", "feature", "in_progress", null, pr = 14),
            run(5, "CI", "topic", "completed", "failure"),
            run(6, "CI", "feature", "completed", "failure", pr = 15, updatedAt = old),
        )
        /* unit-tests failed on the re-run (higher job id) after passing first; lint passed */
        stubJobs(1, "unit-tests" to "success", "lint" to "success", "unit-tests" to "failure")
        stubJobs(5, "build" to "failure")

        pollingService.pollRepo(repo.id.toString())

        val toChannel = capturing.captured.filter { it.second == channel }.map { it.first }
        val toOther = capturing.captured.filter { it.second == other }.map { it.first }
        assertEquals(
            listOf(
                "[owner/repo] PR #12 workflow 'CI' FAILED (unit-tests) - https://github.com/owner/repo/actions/runs/1"
            ),
            toChannel,
        )
        assertEquals(3, toOther.size, toOther.toString())
        assertTrue(
            toOther.any { it.startsWith("[owner/repo] PR #13 workflow 'CI' succeeded (4m12s)") },
            toOther.toString(),
        )
        assertTrue(
            toOther.any { it.startsWith("[owner/repo] main workflow 'CI' succeeded (4m12s)") },
            toOther.toString(),
        )
        assertEquals(
            PipelineOutcome.IN_PROGRESS,
            pipelineRepository.findByRepoAndPipelineId(repo, "4")!!.lastStatus,
        )

        capturing.captured.clear()
        pollingService.pollRepo(repo.id.toString())
        assertEquals(0, capturing.captured.size, capturing.captured.toString())

        /* A retry: run 1 goes back in progress, then succeeds */
        stubRuns(run(1, "CI", "feature", "in_progress", null, pr = 12))
        pollingService.pollRepo(repo.id.toString())
        stubRuns(run(1, "CI", "feature", "completed", "success", pr = 12))
        pollingService.pollRepo(repo.id.toString())
        val retried = capturing.captured.map { it.first to it.second }
        assertEquals(1, retried.size, retried.toString())
        assertEquals(other, retried[0].second)
        assertTrue(retried[0].first.contains("PR #12 workflow 'CI' succeeded"), retried[0].first)
    }

    @Test
    fun `workflow_run webhook reports once, polling does not repeat it, and a re-run reports again`() {
        val repo = seedRepo()
        subscriptionService.subscribe(
            store.defaultInstance(),
            "owner/repo",
            channel,
            listOf(dev.streampack.forge.subscription.PipelineFilter.parse("pipelines")!!),
        )
        stubJobs(77, "unit-tests" to "failure", "docs" to "success")
        val payload =
            """{"action": "completed", "workflow_run": ${run(77, "CI", "feature", "completed", "failure", pr = 21)},
                "repository": {"full_name": "owner/repo", "default_branch": "main"}}"""
        mockMvc
            .post("/webhooks/github") {
                contentType = MediaType.APPLICATION_JSON
                content = payload
                header("X-GitHub-Event", "workflow_run")
                header("X-GitHub-Delivery", "delivery-run-77")
                header("X-Hub-Signature-256", sign(payload.toByteArray()))
            }
            .andExpect { status { isAccepted() } }
        assertEquals(
            listOf(
                "[owner/repo] PR #21 workflow 'CI' FAILED (unit-tests) - https://github.com/owner/repo/actions/runs/77" to
                    channel
            ),
            capturing.captured.toList(),
        )

        stubRuns(run(77, "CI", "feature", "completed", "failure", pr = 21))
        pollingService.pollRepo(repo.id.toString())
        assertEquals(1, capturing.captured.size, capturing.captured.toString())

        /* A re-run: GitHub sends `requested`, which is recorded, then the poll sees it fail again */
        val requested =
            payload
                .replace("\"action\": \"completed\"", "\"action\": \"requested\"")
                .replace("\"status\": \"completed\"", "\"status\": \"queued\"")
        mockMvc
            .post("/webhooks/github") {
                contentType = MediaType.APPLICATION_JSON
                content = requested
                header("X-GitHub-Event", "workflow_run")
                header("X-Hub-Signature-256", sign(requested.toByteArray()))
            }
            .andExpect { status { isAccepted() } }
        assertEquals(1, capturing.captured.size, capturing.captured.toString())
        /* GitHub bumps updated_at when the re-run completes */
        stubRuns(run(77, "CI", "feature", "completed", "failure", pr = 21))
        pollingService.pollRepo(repo.id.toString())
        assertEquals(2, capturing.captured.size, capturing.captured.toString())
        assertTrue(
            capturing.captured[1].first.contains("PR #21 workflow 'CI' FAILED (unit-tests)"),
            capturing.captured[1].first,
        )
    }

    private fun sign(body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return "sha256=" + mac.doFinal(body).joinToString("") { "%02x".format(it) }
    }
}
