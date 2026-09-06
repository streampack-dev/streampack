/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.operation

import com.sun.net.httpserver.HttpServer
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.github.entity.GitHubInstance
import dev.streampack.github.repository.GitHubInstanceRepository
import dev.streampack.github.repository.GitHubRepoRepository
import dev.streampack.github.service.GitHubForgeStore
import dev.streampack.test.ResetDatabaseBeforeEach
import dev.streampack.test.TestSecurityConfiguration
import java.net.InetSocketAddress
import java.util.UUID
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.messaging.support.MessageBuilder

/** `github instance add|list` and `on <host>` selection across the other commands. */
@SpringBootTest(properties = ["GITHUB_INSTANCE_GHE_EXAMPLE_COM_TOKEN=ghp_instance_default"])
@ResetDatabaseBeforeEach
@Import(TestSecurityConfiguration::class)
@ResourceLock("github-api-endpoint")
class GitHubInstanceOperationTests {
    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var instanceRepository: GitHubInstanceRepository
    @Autowired lateinit var repoRepository: GitHubRepoRepository
    @Autowired lateinit var store: GitHubForgeStore

    private lateinit var httpServer: HttpServer

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

    private fun guestMessage(text: String) =
        MessageBuilder.withPayload(text)
            .setHeader(
                Provenance.HEADER,
                Provenance(protocol = Protocol.CONSOLE, replyTo = "local"),
            )
            .build()

    @BeforeEach
    fun setUp() {
        httpServer = HttpServer.create(InetSocketAddress(0), 0)
        httpServer.start()
    }

    @AfterEach
    fun tearDown() {
        httpServer.stop(0)
    }

    private fun localApiUrl() = "http://localhost:${httpServer.address.port}"

    /** Stubs a repo under an API prefix, recording the Authorization header seen. */
    private fun stubRepo(
        prefix: String,
        owner: String,
        name: String,
        seenAuth: MutableList<String?>,
    ) {
        for (path in listOf("", "/issues", "/pulls", "/releases")) {
            httpServer.createContext("$prefix/repos/$owner/$name$path") { exchange ->
                seenAuth.add(exchange.requestHeaders.getFirst("Authorization"))
                val body =
                    if (path.isEmpty()) """{"id": 1, "full_name": "$owner/$name"}""" else "[]"
                exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray()) }
            }
        }
    }

    @Test
    fun `instance list always shows the hosted default`() {
        val result = eventGateway.process(guestMessage("github instance list"))
        val payload =
            assertInstanceOf(OperationResult.Success::class.java, result).payload.toString()
        assertTrue(payload.contains("github.com"), payload)
        assertTrue(payload.contains(GitHubInstance.DEFAULT_API_URL), payload)
    }

    @Test
    fun `instance add derives the api url from the base url and requires admin`() {
        val denied =
            eventGateway.process(guestMessage("github instance add https://ghe.example.com"))
        assertInstanceOf(OperationResult.NotHandled::class.java, denied)

        val result =
            eventGateway.process(adminMessage("github instance add https://ghe.example.com"))
        val payload =
            assertInstanceOf(OperationResult.Success::class.java, result).payload.toString()
        assertTrue(payload.contains("ghe.example.com"), payload)

        val instance = instanceRepository.findByHost("ghe.example.com")
        assertNotNull(instance)
        assertEquals("https://ghe.example.com/api/v3", instance!!.apiUrl)

        val again =
            eventGateway.process(adminMessage("github instance add https://ghe.example.com"))
        assertTrue(
            assertInstanceOf(OperationResult.Success::class.java, again)
                .payload
                .toString()
                .contains("Already")
        )
    }

    @Test
    fun `instance add keeps an explicit api path and stores a literal token with its env key`() {
        val result =
            eventGateway.process(
                adminMessage(
                    "github instance add https://ghe.example.com/api/v3 ghp_literal_default"
                )
            )
        val payload =
            assertInstanceOf(OperationResult.Success::class.java, result).payload.toString()
        assertTrue(payload.contains("GITHUB_INSTANCE_GHE_EXAMPLE_COM_TOKEN"), payload)

        val instance = instanceRepository.findByHost("ghe.example.com")!!
        assertEquals("https://ghe.example.com/api/v3", instance.apiUrl)
        assertEquals("ghp_literal_default", instance.defaultToken?.asStoredValue())
    }

    @Test
    fun `instance add refuses an env reference that is not set`() {
        val result =
            eventGateway.process(
                adminMessage(
                    "github instance add https://ghe.example.com env://GITHUB_NOT_SET_ANYWHERE"
                )
            )
        val error = assertInstanceOf(OperationResult.Error::class.java, result)
        assertTrue(error.message.contains("GITHUB_NOT_SET_ANYWHERE"), error.message)
    }

    @Test
    fun `the same repository can be watched on two instances and each uses its own endpoint and token`() {
        val defaultSeen = mutableListOf<String?>()
        val gheSeen = mutableListOf<String?>()
        stubRepo("", "owner", "repo", defaultSeen)
        stubRepo("/api/v3", "owner", "repo", gheSeen)
        instanceRepository.save(store.defaultInstance().copy(apiUrl = localApiUrl()))
        instanceRepository.save(
            GitHubInstance(
                host = "ghe.example.com",
                apiUrl = "${localApiUrl()}/api/v3",
                defaultToken =
                    dev.streampack.core.model.SecretRef.env("GITHUB_INSTANCE_GHE_EXAMPLE_COM_TOKEN"),
            )
        )

        val hosted = eventGateway.process(adminMessage("github add owner/repo"))
        assertTrue(
            assertInstanceOf(OperationResult.Success::class.java, hosted)
                .payload
                .toString()
                .contains("Watching owner/repo")
        )
        val enterprise =
            eventGateway.process(adminMessage("github add owner/repo on ghe.example.com"))
        val enterprisePayload =
            assertInstanceOf(OperationResult.Success::class.java, enterprise).payload.toString()
        assertTrue(
            enterprisePayload.contains("Watching ghe.example.com owner/repo"),
            enterprisePayload,
        )

        assertEquals(2, repoRepository.findAll().count { it.owner == "owner" && it.name == "repo" })
        assertTrue(
            defaultSeen.isNotEmpty() && defaultSeen.all { it == null },
            defaultSeen.toString(),
        )
        assertTrue(
            gheSeen.isNotEmpty() &&
                gheSeen.all { it != null && it.contains("ghp_instance_default") },
            gheSeen.toString(),
        )

        val list = eventGateway.process(adminMessage("github list"))
        val listed = assertInstanceOf(OperationResult.Success::class.java, list).payload.toString()
        assertTrue(listed.contains("ghe.example.com owner/repo"), listed)

        val unknown =
            eventGateway.process(adminMessage("github add owner/repo on nowhere.example.com"))
        val error = assertInstanceOf(OperationResult.Error::class.java, unknown)
        assertTrue(error.message.contains("nowhere.example.com"), error.message)
    }

    @Test
    fun `subscribe unsubscribe and remove accept on host`() {
        val seen = mutableListOf<String?>()
        stubRepo("/api/v3", "owner", "repo", seen)
        instanceRepository.save(
            GitHubInstance(host = "ghe.example.com", apiUrl = "${localApiUrl()}/api/v3")
        )
        eventGateway.process(adminMessage("github add owner/repo on ghe.example.com"))

        val subscribe =
            eventGateway.process(adminMessage("github subscribe owner/repo on ghe.example.com"))
        assertEquals(
            "Subscribed to ghe.example.com owner/repo",
            assertInstanceOf(OperationResult.Success::class.java, subscribe).payload,
        )
        val hostedMiss = eventGateway.process(adminMessage("github subscribe owner/repo"))
        assertInstanceOf(OperationResult.Error::class.java, hostedMiss)

        val explicit =
            eventGateway.process(
                adminMessage(
                    "github subscribe owner/repo on ghe.example.com to irc://libera/%23java"
                )
            )
        assertInstanceOf(OperationResult.Success::class.java, explicit)

        val unsubscribe =
            eventGateway.process(adminMessage("github unsubscribe owner/repo on ghe.example.com"))
        assertEquals(
            "Unsubscribed from ghe.example.com owner/repo",
            assertInstanceOf(OperationResult.Success::class.java, unsubscribe).payload,
        )

        val remove =
            eventGateway.process(adminMessage("github remove owner/repo on ghe.example.com"))
        val removed =
            assertInstanceOf(OperationResult.Success::class.java, remove).payload.toString()
        assertTrue(removed.contains("Removed ghe.example.com owner/repo"), removed)
        assertTrue(removed.contains("1 subscriptions deactivated"), removed)
    }
}
