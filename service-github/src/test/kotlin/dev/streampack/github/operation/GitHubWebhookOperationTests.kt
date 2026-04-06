/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.operation

import com.sun.net.httpserver.HttpServer
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.github.model.DeliveryMode
import dev.streampack.github.repository.GitHubRepoRepository
import dev.streampack.github.service.GitHubApiClient
import dev.streampack.github.service.WebhookSecretCipher
import dev.streampack.test.TestSecurityConfiguration
import java.net.InetSocketAddress
import java.util.UUID
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

@SpringBootTest(properties = ["streampack.github.webhook-base-url=https://hooks.example.com"])
@Transactional
@Import(TestSecurityConfiguration::class)
class GitHubWebhookOperationTests {

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var repoRepository: GitHubRepoRepository
    @Autowired lateinit var cipher: WebhookSecretCipher

    private lateinit var httpServer: HttpServer
    private var originalApiEndpoint: String? = null

    private val adminUser =
        UserPrincipal(
            id = UUID.randomUUID(),
            username = "admin",
            displayName = "Admin",
            role = Role.ADMIN,
        )

    private fun provenance() =
        Provenance(protocol = Protocol.CONSOLE, serviceId = "", replyTo = "local", user = adminUser)

    private fun message(command: String) =
        MessageBuilder.withPayload(command).setHeader(Provenance.HEADER, provenance()).build()

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

    private fun stubRepo(owner: String, name: String) {
        httpServer.createContext("/repos/$owner/$name") { exchange ->
            val body = """{"id": 1, "full_name": "$owner/$name"}"""
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        httpServer.createContext("/repos/$owner/$name/issues") { exchange ->
            val json =
                """[
                {"number": 1, "title": "Issue 1", "html_url": "https://github.com/$owner/$name/issues/1"}
            ]"""
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }
        httpServer.createContext("/repos/$owner/$name/pulls") { exchange ->
            val json =
                """[
                {"number": 2, "title": "PR 1", "html_url": "https://github.com/$owner/$name/pull/2"}
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
    fun `github webhook registers repo enables webhook mode and stores encrypted secret`() {
        stubRepo("owner", "repo")
        val result = eventGateway.process(message("github webhook owner/repo"))
        val success = assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals(
            true,
            success.payload.toString().contains("https://hooks.example.com/webhooks/github"),
        )

        val repo = repoRepository.findByOwnerAndName("owner", "repo")
        assertNotNull(repo)
        assertEquals(DeliveryMode.WEBHOOK, repo!!.deliveryMode)
        assertNotNull(repo.webhookSecret)
        val decrypted = cipher.decrypt(repo.webhookSecret!!)
        assertEquals(64, decrypted.length)
    }
}
