/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.operation

import com.sun.net.httpserver.HttpServer
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.github.repository.GitHubRepoRepository
import dev.streampack.github.service.GitHubApiClient
import dev.streampack.test.ResetDatabaseBeforeEach
import dev.streampack.test.TestSecurityConfiguration
import java.net.InetSocketAddress
import java.util.UUID
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
import org.springframework.messaging.support.MessageBuilder

/** Token handling on `github add`: literal tokens are announced, env references are resolved. */
@SpringBootTest(properties = ["GITHUB_TEST_TOKEN=ghp_from_environment"])
@ResetDatabaseBeforeEach
@Import(TestSecurityConfiguration::class)
@ResourceLock("github-api-endpoint")
class GitHubAddOperationTokenTests {
    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var repoRepository: GitHubRepoRepository

    private lateinit var httpServer: HttpServer
    private var originalApiEndpoint: String? = null
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
                    protocol = Protocol.CONSOLE,
                    serviceId = "",
                    replyTo = "local",
                    user = adminUser,
                ),
            )
            .build()

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

    /** Stubs the repo and its sub-resources, recording the Authorization header seen. */
    private fun stubRepo(owner: String, name: String, seenAuth: MutableList<String?>) {
        for (path in listOf("", "/issues", "/pulls", "/releases")) {
            httpServer.createContext("/repos/$owner/$name$path") { exchange ->
                seenAuth.add(exchange.requestHeaders.getFirst("Authorization"))
                val body =
                    if (path.isEmpty()) """{"id": 1, "full_name": "$owner/$name"}""" else "[]"
                exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray()) }
            }
        }
    }

    @Test
    fun `literal token is stored and the response names the environment variable to set`() {
        val seen = mutableListOf<String?>()
        stubRepo("owner", "repo", seen)

        val result = eventGateway.process(adminMessage("github add owner/repo ghp_literal_value"))

        val payload =
            assertInstanceOf(OperationResult.Success::class.java, result).payload.toString()
        assertTrue(payload.contains("Watching owner/repo"), payload)
        assertTrue(payload.contains("GITHUB_OWNER_REPO_TOKEN"), payload)
        assertTrue(seen.all { it != null && it.contains("ghp_literal_value") }, seen.toString())
        assertEquals(
            "ghp_literal_value",
            repoRepository.findByOwnerAndName("owner", "repo")!!.token?.asStoredValue(),
        )
    }

    @Test
    fun `env reference token is stored as a reference and resolved for API calls`() {
        val seen = mutableListOf<String?>()
        stubRepo("owner", "envrepo", seen)

        val result =
            eventGateway.process(adminMessage("github add owner/envrepo env://GITHUB_TEST_TOKEN"))

        val payload =
            assertInstanceOf(OperationResult.Success::class.java, result).payload.toString()
        assertTrue(payload.contains("Watching owner/envrepo"), payload)
        assertTrue(!payload.contains("before the next restart"), payload)
        assertTrue(seen.all { it != null && it.contains("ghp_from_environment") }, seen.toString())
        assertEquals(
            "env://GITHUB_TEST_TOKEN",
            repoRepository.findByOwnerAndName("owner", "envrepo")!!.token?.asStoredValue(),
        )
    }

    @Test
    fun `env reference to an unset variable is refused`() {
        val result =
            eventGateway.process(adminMessage("github add owner/x env://GITHUB_NOT_SET_ANYWHERE"))
        val error = assertInstanceOf(OperationResult.Error::class.java, result)
        assertTrue(error.message.contains("GITHUB_NOT_SET_ANYWHERE"), error.message)
    }
}
