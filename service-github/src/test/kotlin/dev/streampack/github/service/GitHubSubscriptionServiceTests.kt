/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.service

import com.sun.net.httpserver.HttpServer
import dev.streampack.github.model.AddRepoOutcome
import dev.streampack.github.model.GitHubSubscriptionOutcome
import dev.streampack.github.model.RemoveRepoOutcome
import dev.streampack.github.repository.GitHubReleaseRepository
import dev.streampack.github.repository.GitHubRepoRepository
import dev.streampack.test.TestSecurityConfiguration
import java.net.InetSocketAddress
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
@Import(TestSecurityConfiguration::class)
@ResourceLock("github-api-endpoint")
class GitHubSubscriptionServiceTests {

    @Autowired lateinit var subscriptionService: GitHubSubscriptionService
    @Autowired lateinit var repoRepository: GitHubRepoRepository
    @Autowired lateinit var releaseRepository: GitHubReleaseRepository

    private lateinit var httpServer: HttpServer
    private var originalApiEndpoint: String? = null

    @BeforeEach
    fun setUp() {
        originalApiEndpoint = GitHubApiClient.apiEndpoint
        httpServer = HttpServer.create(InetSocketAddress(0), 0)
        httpServer.start()
        GitHubApiClient.apiEndpoint = "http://localhost:${httpServer.address.port}"
    }

    @AfterEach
    fun tearDown() {
        httpServer.stop(0)
        GitHubApiClient.apiEndpoint = originalApiEndpoint
    }

    private fun stubValidRepo(owner: String, name: String) {
        httpServer.createContext("/repos/$owner/$name") { exchange ->
            val body = """{"id": 1, "full_name": "$owner/$name"}"""
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        httpServer.createContext("/repos/$owner/$name/issues") { exchange ->
            val json =
                """[
                {"number": 1, "title": "Issue 1", "html_url": "https://github.com/$owner/$name/issues/1"},
                {"number": 2, "title": "Issue 2", "html_url": "https://github.com/$owner/$name/issues/2"}
            ]"""
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }
        httpServer.createContext("/repos/$owner/$name/pulls") { exchange ->
            val json =
                """[
                {"number": 3, "title": "PR 1", "html_url": "https://github.com/$owner/$name/pull/3"}
            ]"""
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }
        httpServer.createContext("/repos/$owner/$name/releases") { exchange ->
            val json =
                """[
                {"tag_name": "v1.0.0", "name": "First Release", "html_url": "https://github.com/$owner/$name/releases/tag/v1.0.0"}
            ]"""
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }
    }

    @Test
    fun `addRepo with valid repo seeds baseline and returns Added`() {
        stubValidRepo("owner", "repo")
        val outcome = subscriptionService.addRepo("owner/repo", null)
        assertInstanceOf(AddRepoOutcome.Added::class.java, outcome)
        val added = outcome as AddRepoOutcome.Added
        assertEquals("owner", added.repo.owner)
        assertEquals("repo", added.repo.name)
        assertEquals(2, added.issueCount)
        assertEquals(1, added.prCount)
        assertEquals(1, added.releaseCount)
        assertEquals(2, added.repo.highestIssueNumber)
        assertEquals(3, added.repo.highestPrNumber)
    }

    @Test
    fun `addRepo seeds release tags`() {
        stubValidRepo("owner", "repo")
        subscriptionService.addRepo("owner/repo", null)
        val repo = repoRepository.findByOwnerAndName("owner", "repo")!!
        val releases = releaseRepository.findByRepoAndTagIn(repo, listOf("v1.0.0"))
        assertEquals(1, releases.size)
        assertEquals("v1.0.0", releases[0].tag)
    }

    @Test
    fun `addRepo with existing repo returns AlreadyExists`() {
        stubValidRepo("owner", "repo")
        subscriptionService.addRepo("owner/repo", null)
        val outcome = subscriptionService.addRepo("owner/repo", null)
        assertInstanceOf(AddRepoOutcome.AlreadyExists::class.java, outcome)
    }

    @Test
    fun `addRepo with invalid format returns InvalidRepo`() {
        val outcome = subscriptionService.addRepo("no-slash", null)
        assertInstanceOf(AddRepoOutcome.InvalidRepo::class.java, outcome)
    }

    @Test
    fun `addRepo with nonexistent repo returns ApiFailed`() {
        httpServer.createContext("/repos/owner/missing") { exchange ->
            exchange.sendResponseHeaders(404, -1)
        }
        val outcome = subscriptionService.addRepo("owner/missing", null)
        assertInstanceOf(AddRepoOutcome.ApiFailed::class.java, outcome)
    }

    @Test
    fun `subscribe and unsubscribe lifecycle`() {
        stubValidRepo("owner", "repo")
        subscriptionService.addRepo("owner/repo", null)

        val sub = subscriptionService.subscribe("owner/repo", "irc://libera/%23java")
        assertInstanceOf(GitHubSubscriptionOutcome.Subscribed::class.java, sub)

        val dupe = subscriptionService.subscribe("owner/repo", "irc://libera/%23java")
        assertInstanceOf(GitHubSubscriptionOutcome.AlreadySubscribed::class.java, dupe)

        val unsub = subscriptionService.unsubscribe("owner/repo", "irc://libera/%23java")
        assertInstanceOf(GitHubSubscriptionOutcome.Unsubscribed::class.java, unsub)

        val notSub = subscriptionService.unsubscribe("owner/repo", "irc://libera/%23java")
        assertInstanceOf(GitHubSubscriptionOutcome.NotSubscribed::class.java, notSub)
    }

    @Test
    fun `subscribe reactivates inactive subscription`() {
        stubValidRepo("owner", "repo")
        subscriptionService.addRepo("owner/repo", null)
        subscriptionService.subscribe("owner/repo", "irc://libera/%23java")
        subscriptionService.unsubscribe("owner/repo", "irc://libera/%23java")

        val result = subscriptionService.subscribe("owner/repo", "irc://libera/%23java")
        assertInstanceOf(GitHubSubscriptionOutcome.Subscribed::class.java, result)
    }

    @Test
    fun `subscribe to nonexistent repo returns RepoNotFound`() {
        val outcome = subscriptionService.subscribe("owner/missing", "irc://libera/%23java")
        assertInstanceOf(GitHubSubscriptionOutcome.RepoNotFound::class.java, outcome)
    }

    @Test
    fun `removeRepo deactivates repo and subscriptions`() {
        stubValidRepo("owner", "repo")
        subscriptionService.addRepo("owner/repo", null)
        subscriptionService.subscribe("owner/repo", "irc://libera/%23java")

        val outcome = subscriptionService.removeRepo("owner/repo")
        assertInstanceOf(RemoveRepoOutcome.Removed::class.java, outcome)
        val removed = outcome as RemoveRepoOutcome.Removed
        assertEquals(1, removed.subscriptionsDeactivated)
    }

    @Test
    fun `removeRepo on already inactive returns AlreadyInactive`() {
        stubValidRepo("owner", "repo")
        subscriptionService.addRepo("owner/repo", null)
        subscriptionService.removeRepo("owner/repo")
        val outcome = subscriptionService.removeRepo("owner/repo")
        assertInstanceOf(RemoveRepoOutcome.AlreadyInactive::class.java, outcome)
    }

    @Test
    fun `removeRepo on nonexistent returns RepoNotFound`() {
        val outcome = subscriptionService.removeRepo("owner/missing")
        assertInstanceOf(RemoveRepoOutcome.RepoNotFound::class.java, outcome)
    }

    @Test
    fun `listRepos returns all repos`() {
        stubValidRepo("owner", "repo")
        subscriptionService.addRepo("owner/repo", null)
        val repos = subscriptionService.listRepos()
        assertTrue(repos.isNotEmpty())
    }

    @Test
    fun `listSubscriptions returns active subscriptions for destination`() {
        stubValidRepo("owner", "repo")
        subscriptionService.addRepo("owner/repo", null)
        subscriptionService.subscribe("owner/repo", "irc://libera/%23java")

        val subs = subscriptionService.listSubscriptions("irc://libera/%23java")
        assertEquals(1, subs.size)
    }
}
