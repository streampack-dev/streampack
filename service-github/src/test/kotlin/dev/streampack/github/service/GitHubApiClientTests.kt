/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.service

import com.sun.net.httpserver.HttpServer
import dev.streampack.test.TestSecurityConfiguration
import java.net.InetSocketAddress
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest
@Import(TestSecurityConfiguration::class)
class GitHubApiClientTests {

    @Autowired lateinit var apiClient: GitHubApiClient

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

    @Test
    fun `validateRepo returns true for 200 response`() {
        httpServer.createContext("/repos/owner/repo") { exchange ->
            val body = """{"id": 1, "full_name": "owner/repo"}"""
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }

        assertTrue(apiClient.validateRepo("owner", "repo", null))
    }

    @Test
    fun `validateRepo returns false for 404 response`() {
        httpServer.createContext("/repos/owner/missing") { exchange ->
            exchange.sendResponseHeaders(404, -1)
        }

        assertFalse(apiClient.validateRepo("owner", "missing", null))
    }

    @Test
    fun `validateRepo sends authorization header when token provided`() {
        var authHeader: String? = null
        httpServer.createContext("/repos/owner/private") { exchange ->
            authHeader = exchange.requestHeaders.getFirst("Authorization")
            val body = """{"id": 1, "full_name": "owner/private"}"""
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }

        apiClient.validateRepo("owner", "private", "ghp_test123")
        assertTrue(authHeader != null && authHeader.contains("ghp_test123"))
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
    fun `fetchIssues returns issues excluding pull requests`() {
        stubRepo("owner", "repo")
        httpServer.createContext("/repos/owner/repo/issues") { exchange ->
            val json =
                """[
                {"number": 1, "title": "Bug report", "html_url": "https://github.com/owner/repo/issues/1"},
                {"number": 2, "title": "Feature PR", "html_url": "https://github.com/owner/repo/pull/2", "pull_request": {"url": "..."}},
                {"number": 3, "title": "Another bug", "html_url": "https://github.com/owner/repo/issues/3"}
            ]"""
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }

        val issues = apiClient.fetchIssues("owner", "repo", null, 0)
        assertEquals(2, issues.size)
        assertEquals("Bug report", issues[0].title)
        assertEquals("Another bug", issues[1].title)
        assertFalse(issues[0].pullRequest)
    }

    @Test
    fun `fetchIssues filters by sinceNumber`() {
        stubRepo("owner", "repo")
        httpServer.createContext("/repos/owner/repo/issues") { exchange ->
            val json =
                """[
                {"number": 1, "title": "Old issue", "html_url": "https://github.com/owner/repo/issues/1"},
                {"number": 5, "title": "New issue", "html_url": "https://github.com/owner/repo/issues/5"}
            ]"""
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }

        val issues = apiClient.fetchIssues("owner", "repo", null, 3)
        assertEquals(1, issues.size)
        assertEquals(5, issues[0].number)
    }

    @Test
    fun `fetchPulls returns pull requests filtered by sinceNumber`() {
        stubRepo("owner", "repo")
        httpServer.createContext("/repos/owner/repo/pulls") { exchange ->
            val json =
                """[
                {"number": 1, "title": "Old PR", "html_url": "https://github.com/owner/repo/pull/1"},
                {"number": 10, "title": "New PR", "html_url": "https://github.com/owner/repo/pull/10"}
            ]"""
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }

        val pulls = apiClient.fetchPulls("owner", "repo", null, 5)
        assertEquals(1, pulls.size)
        assertEquals(10, pulls[0].number)
        assertEquals("New PR", pulls[0].title)
        assertTrue(pulls[0].pullRequest)
    }

    @Test
    fun `fetchReleases returns all releases`() {
        stubRepo("owner", "repo")
        httpServer.createContext("/repos/owner/repo/releases") { exchange ->
            val json =
                """[
                {"tag_name": "v1.0.0", "name": "First Release", "html_url": "https://github.com/owner/repo/releases/tag/v1.0.0"},
                {"tag_name": "v1.1.0", "name": null, "html_url": "https://github.com/owner/repo/releases/tag/v1.1.0"}
            ]"""
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }

        val releases = apiClient.fetchReleases("owner", "repo", null)
        assertEquals(2, releases.size)
        assertEquals("v1.0.0", releases[0].tagName)
        assertEquals("First Release", releases[0].name)
        assertEquals("v1.1.0", releases[1].tagName)
    }

    @Test
    fun `fetchIssues returns empty list on API error`() {
        stubRepo("owner", "repo")
        httpServer.createContext("/repos/owner/repo/issues") { exchange ->
            exchange.sendResponseHeaders(500, -1)
        }

        val issues = apiClient.fetchIssues("owner", "repo", null, 0)
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `validateRepo works without token`() {
        var authHeader: String? = null
        httpServer.createContext("/repos/owner/repo") { exchange ->
            authHeader = exchange.requestHeaders.getFirst("Authorization")
            val body = """{"id": 1, "full_name": "owner/repo"}"""
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }

        assertTrue(apiClient.validateRepo("owner", "repo", null))
        assertTrue(authHeader == null || !authHeader!!.contains("ghp_"))
    }
}
