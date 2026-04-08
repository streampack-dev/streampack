/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.operation

import com.sun.net.httpserver.HttpServer
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.github.service.GitHubApiClient
import dev.streampack.test.TestSecurityConfiguration
import java.net.InetSocketAddress
import java.util.UUID
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
@Import(TestSecurityConfiguration::class)
@ResourceLock("github-api-endpoint")
class GitHubAddOperationTests {

    @Autowired lateinit var eventGateway: EventGateway

    private lateinit var httpServer: HttpServer
    private var originalApiEndpoint: String? = null

    private val adminUser =
        UserPrincipal(
            id = UUID.randomUUID(),
            username = "admin",
            displayName = "Admin",
            role = Role.ADMIN,
        )

    private fun adminProvenance() =
        Provenance(protocol = Protocol.CONSOLE, serviceId = "", replyTo = "local", user = adminUser)

    private fun adminMessage(text: String) =
        MessageBuilder.withPayload(text).setHeader(Provenance.HEADER, adminProvenance()).build()

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
                """[{"number": 3, "title": "PR 1", "html_url": "https://github.com/$owner/$name/pull/3"}]"""
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }
        httpServer.createContext("/repos/$owner/$name/releases") { exchange ->
            val json =
                """[{"tag_name": "v1.0.0", "name": "First", "html_url": "https://github.com/$owner/$name/releases/tag/v1.0.0"}]"""
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }
    }

    @Test
    fun `github add with valid repo returns success with counts`() {
        stubValidRepo("owner", "repo")
        val result = eventGateway.process(adminMessage("github add owner/repo"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload.toString()
        assertTrue(payload.contains("Watching owner/repo"))
        assertTrue(payload.contains("2 issues"))
        assertTrue(payload.contains("1 PRs"))
        assertTrue(payload.contains("1 releases"))
    }

    @Test
    fun `github add is case insensitive`() {
        stubValidRepo("owner", "repo")
        val result = eventGateway.process(adminMessage("GitHub Add owner/repo"))
        assertInstanceOf(OperationResult.Success::class.java, result)
    }

    @Test
    fun `github add with invalid format returns error`() {
        val result = eventGateway.process(adminMessage("github add noslash"))
        assertInstanceOf(OperationResult.Error::class.java, result)
        assertTrue((result as OperationResult.Error).message.contains("Invalid repository"))
    }

    @Test
    fun `github add without admin role is not handled`() {
        val guestProvenance = Provenance(protocol = Protocol.CONSOLE, replyTo = "local")
        val msg =
            MessageBuilder.withPayload("github add owner/repo")
                .setHeader(Provenance.HEADER, guestProvenance)
                .build()

        val result = eventGateway.process(msg)
        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }

    @Test
    fun `github add with nonexistent repo returns error`() {
        httpServer.createContext("/repos/owner/missing") { exchange ->
            exchange.sendResponseHeaders(404, -1)
        }
        val result = eventGateway.process(adminMessage("github add owner/missing"))
        assertInstanceOf(OperationResult.Error::class.java, result)
        assertTrue((result as OperationResult.Error).message.contains("Failed to access"))
    }

    @Test
    fun `github add with duplicate repo returns already watching`() {
        stubValidRepo("owner", "repo")
        eventGateway.process(adminMessage("github add owner/repo"))
        val result = eventGateway.process(adminMessage("github add owner/repo"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertTrue(
            (result as OperationResult.Success).payload.toString().contains("Already watching")
        )
    }

    @Test
    fun `text that does not match github add is not handled`() {
        val result = eventGateway.process(adminMessage("just a regular message"))
        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }
}
