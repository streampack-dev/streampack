/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.service

import com.sun.net.httpserver.HttpServer
import dev.streampack.core.integration.EgressSubscriber
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.github.entity.GitHubRepo
import dev.streampack.github.entity.GitHubSubscription
import dev.streampack.github.repository.GitHubReleaseRepository
import dev.streampack.github.repository.GitHubRepoRepository
import dev.streampack.github.repository.GitHubSubscriptionRepository
import dev.streampack.test.TestSecurityConfiguration
import java.net.InetSocketAddress
import java.time.Instant
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
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.messaging.SubscribableChannel

@SpringBootTest
@Import(TestSecurityConfiguration::class)
@ResourceLock("github-api-endpoint")
class GitHubPollingServiceTests {

    @TestConfiguration
    class CapturingEgressConfig {
        @Bean
        fun capturingEgressSubscriber(
            @Qualifier("egressChannel") egressChannel: SubscribableChannel
        ): CapturingEgressSubscriber {
            val subscriber = CapturingEgressSubscriber()
            egressChannel.subscribe(subscriber)
            return subscriber
        }
    }

    class CapturingEgressSubscriber : EgressSubscriber() {
        val captured = CopyOnWriteArrayList<Pair<OperationResult, Provenance>>()

        override fun matches(provenance: Provenance): Boolean = true

        override fun deliver(result: OperationResult, provenance: Provenance) {
            captured.add(result to provenance)
        }

        fun clear() = captured.clear()
    }

    @Autowired lateinit var pollingService: GitHubPollingService
    @Autowired lateinit var repoRepository: GitHubRepoRepository
    @Autowired lateinit var releaseRepository: GitHubReleaseRepository
    @Autowired lateinit var subscriptionRepository: GitHubSubscriptionRepository
    @Autowired lateinit var capturingEgressSubscriber: CapturingEgressSubscriber

    private lateinit var httpServer: HttpServer
    private var originalApiEndpoint: String? = null

    @BeforeEach
    fun setUp() {
        capturingEgressSubscriber.clear()
        originalApiEndpoint = GitHubApiClient.apiEndpoint
        httpServer = HttpServer.create(InetSocketAddress(0), 0)
        httpServer.start()
        GitHubApiClient.apiEndpoint = "http://localhost:${httpServer.address.port}"
    }

    @AfterEach
    fun tearDown() {
        httpServer.stop(0)
        GitHubApiClient.apiEndpoint = originalApiEndpoint
        subscriptionRepository.deleteAll()
        releaseRepository.deleteAll()
        repoRepository.deleteAll()
    }

    private fun createRepo(
        owner: String,
        name: String,
        highestIssue: Int = 0,
        highestPr: Int = 0,
    ): GitHubRepo {
        return repoRepository.save(
            GitHubRepo(
                owner = owner,
                name = name,
                highestIssueNumber = highestIssue,
                highestPrNumber = highestPr,
                lastPolledAt = Instant.now().minusSeconds(3600),
            )
        )
    }

    private fun subscribeRepo(repo: GitHubRepo, destinationUri: String): GitHubSubscription {
        return subscriptionRepository.save(
            GitHubSubscription(repo = repo, destinationUri = destinationUri)
        )
    }

    /** Stub the repo endpoint so getRepository() succeeds before sub-resource calls */
    private fun stubRepo(owner: String, name: String) {
        httpServer.createContext("/repos/$owner/$name") { exchange ->
            val body = """{"id": 1, "full_name": "$owner/$name"}"""
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
    }

    @Test
    fun `polling detects new issues and notifies subscribers`() {
        val repo = createRepo("owner", "repo", highestIssue = 1)
        val destination =
            Provenance(protocol = Protocol.IRC, serviceId = "libera", replyTo = "#java")
        subscribeRepo(repo, destination.encode())

        stubRepo("owner", "repo")
        httpServer.createContext("/repos/owner/repo/issues") { exchange ->
            val json =
                """[
                {"number": 1, "title": "Old", "html_url": "https://github.com/owner/repo/issues/1"},
                {"number": 2, "title": "New Bug", "html_url": "https://github.com/owner/repo/issues/2"}
            ]"""
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }
        httpServer.createContext("/repos/owner/repo/pulls") { exchange ->
            val json = "[]"
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }
        httpServer.createContext("/repos/owner/repo/releases") { exchange ->
            val json = "[]"
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }

        pollingService.pollRepo(repo.id.toString())

        assertEquals(1, capturingEgressSubscriber.captured.size)
        val (result, provenance) = capturingEgressSubscriber.captured[0]
        assertEquals(Protocol.IRC, provenance.protocol)
        assertTrue(result is OperationResult.Success)
        val text = (result as OperationResult.Success).payload.toString()
        assertTrue(text.contains("[owner/repo]"))
        assertTrue(text.contains("New issue #2"))
        assertTrue(text.contains("New Bug"))
    }

    @Test
    fun `polling detects new PRs and notifies subscribers`() {
        val repo = createRepo("owner", "repo", highestPr = 5)
        val destination = Provenance(protocol = Protocol.CONSOLE, replyTo = "local")
        subscribeRepo(repo, destination.encode())

        stubRepo("owner", "repo")
        httpServer.createContext("/repos/owner/repo/issues") { exchange ->
            val json = "[]"
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }
        httpServer.createContext("/repos/owner/repo/pulls") { exchange ->
            val json =
                """[
                {"number": 5, "title": "Old PR", "html_url": "https://github.com/owner/repo/pull/5"},
                {"number": 6, "title": "New Feature", "html_url": "https://github.com/owner/repo/pull/6"}
            ]"""
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }
        httpServer.createContext("/repos/owner/repo/releases") { exchange ->
            val json = "[]"
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }

        pollingService.pollRepo(repo.id.toString())

        assertEquals(1, capturingEgressSubscriber.captured.size)
        val text =
            (capturingEgressSubscriber.captured[0].first as OperationResult.Success)
                .payload
                .toString()
        assertTrue(text.contains("New PR #6"))
        assertTrue(text.contains("New Feature"))
    }

    @Test
    fun `polling detects new releases and notifies subscribers`() {
        val repo = createRepo("owner", "repo")
        val destination = Provenance(protocol = Protocol.CONSOLE, replyTo = "local")
        subscribeRepo(repo, destination.encode())

        stubRepo("owner", "repo")
        httpServer.createContext("/repos/owner/repo/issues") { exchange ->
            val json = "[]"
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }
        httpServer.createContext("/repos/owner/repo/pulls") { exchange ->
            val json = "[]"
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }
        httpServer.createContext("/repos/owner/repo/releases") { exchange ->
            val json =
                """[
                {"tag_name": "v2.0.0", "name": "Major Release", "html_url": "https://github.com/owner/repo/releases/tag/v2.0.0"}
            ]"""
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }

        pollingService.pollRepo(repo.id.toString())

        assertEquals(1, capturingEgressSubscriber.captured.size)
        val text =
            (capturingEgressSubscriber.captured[0].first as OperationResult.Success)
                .payload
                .toString()
        assertTrue(text.contains("New release v2.0.0"))
    }

    @Test
    fun `no new items produces no notifications`() {
        val repo = createRepo("owner", "repo", highestIssue = 5, highestPr = 3)
        val destination = Provenance(protocol = Protocol.CONSOLE, replyTo = "local")
        subscribeRepo(repo, destination.encode())

        stubRepo("owner", "repo")
        httpServer.createContext("/repos/owner/repo/issues") { exchange ->
            val json =
                """[{"number": 1, "title": "Old", "html_url": "https://github.com/owner/repo/issues/1"}]"""
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }
        httpServer.createContext("/repos/owner/repo/pulls") { exchange ->
            val json = "[]"
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }
        httpServer.createContext("/repos/owner/repo/releases") { exchange ->
            val json = "[]"
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }

        pollingService.pollRepo(repo.id.toString())

        assertEquals(0, capturingEgressSubscriber.captured.size)
    }

    @Test
    fun `unsubscribed repos produce no notifications`() {
        val repo = createRepo("owner", "repo")

        stubRepo("owner", "repo")
        httpServer.createContext("/repos/owner/repo/issues") { exchange ->
            val json =
                """[{"number": 1, "title": "New", "html_url": "https://github.com/owner/repo/issues/1"}]"""
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }
        httpServer.createContext("/repos/owner/repo/pulls") { exchange ->
            val json = "[]"
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }
        httpServer.createContext("/repos/owner/repo/releases") { exchange ->
            val json = "[]"
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }

        pollingService.pollRepo(repo.id.toString())

        // Baseline updated but no notifications
        assertEquals(0, capturingEgressSubscriber.captured.size)
        val updated = repoRepository.findByOwnerAndName("owner", "repo")!!
        assertEquals(1, updated.highestIssueNumber)
    }

    @Test
    fun `multiple subscribers each receive notifications`() {
        val repo = createRepo("owner", "repo")
        val irc = Provenance(protocol = Protocol.IRC, serviceId = "libera", replyTo = "#java")
        val console = Provenance(protocol = Protocol.CONSOLE, replyTo = "local")
        subscribeRepo(repo, irc.encode())
        subscribeRepo(repo, console.encode())

        stubRepo("owner", "repo")
        httpServer.createContext("/repos/owner/repo/issues") { exchange ->
            val json =
                """[{"number": 1, "title": "Bug", "html_url": "https://github.com/owner/repo/issues/1"}]"""
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }
        httpServer.createContext("/repos/owner/repo/pulls") { exchange ->
            val json = "[]"
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }
        httpServer.createContext("/repos/owner/repo/releases") { exchange ->
            val json = "[]"
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }

        pollingService.pollRepo(repo.id.toString())

        assertEquals(2, capturingEgressSubscriber.captured.size)
        val protocols = capturingEgressSubscriber.captured.map { it.second.protocol }.toSet()
        assertTrue(protocols.contains(Protocol.IRC))
        assertTrue(protocols.contains(Protocol.CONSOLE))
    }

    @Test
    fun `inactive repos are not polled`() {
        val repo =
            repoRepository.save(
                GitHubRepo(
                    owner = "owner",
                    name = "inactive",
                    active = false,
                    lastPolledAt = Instant.now().minusSeconds(3600),
                )
            )
        subscribeRepo(repo, Provenance(protocol = Protocol.CONSOLE, replyTo = "local").encode())

        // No HTTP stubs needed - should not be called
        pollingService.pollAllRepos()
        assertEquals(0, capturingEgressSubscriber.captured.size)
    }

    @Test
    fun `one failing repo does not block others`() {
        val failing = createRepo("owner", "failing")
        val working = createRepo("owner", "working")
        subscribeRepo(working, Provenance(protocol = Protocol.CONSOLE, replyTo = "local").encode())

        // Failing repo - stub repo endpoint but sub-resource calls return 500
        stubRepo("owner", "failing")
        httpServer.createContext("/repos/owner/failing/issues") { exchange ->
            exchange.sendResponseHeaders(500, -1)
        }
        httpServer.createContext("/repos/owner/failing/pulls") { exchange ->
            exchange.sendResponseHeaders(500, -1)
        }
        httpServer.createContext("/repos/owner/failing/releases") { exchange ->
            exchange.sendResponseHeaders(500, -1)
        }

        // Working repo has new items
        stubRepo("owner", "working")
        httpServer.createContext("/repos/owner/working/issues") { exchange ->
            val json =
                """[{"number": 1, "title": "Bug", "html_url": "https://github.com/owner/working/issues/1"}]"""
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }
        httpServer.createContext("/repos/owner/working/pulls") { exchange ->
            val json = "[]"
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }
        httpServer.createContext("/repos/owner/working/releases") { exchange ->
            val json = "[]"
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }

        pollingService.pollAllRepos()

        assertTrue(capturingEgressSubscriber.captured.isNotEmpty())
    }
}
