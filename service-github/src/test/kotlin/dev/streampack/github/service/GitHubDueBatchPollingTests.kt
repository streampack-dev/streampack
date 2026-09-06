/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.service

import com.sun.net.httpserver.HttpServer
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.github.DefaultInstanceEndpoint
import dev.streampack.github.entity.GitHubRepo
import dev.streampack.github.repository.GitHubInstanceRepository
import dev.streampack.github.repository.GitHubRepoRepository
import dev.streampack.test.ResetDatabaseBeforeEach
import dev.streampack.test.TestSecurityConfiguration
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
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

/** GitHub repositories are polled in bounded batches, oldest due first, with backoff (#69). */
@SpringBootTest(
    properties =
        [
            "streampack.github.batch-size=2",
            "streampack.github.poll-interval=PT1H",
            "streampack.github.max-backoff=PT4H",
        ]
)
@ResetDatabaseBeforeEach
@Import(TestSecurityConfiguration::class)
@ResourceLock("github-api-endpoint")
class GitHubDueBatchPollingTests {
    @Autowired lateinit var pollingService: GitHubPollingService
    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var repoRepository: GitHubRepoRepository
    @Autowired lateinit var instanceRepository: GitHubInstanceRepository
    @Autowired lateinit var store: GitHubForgeStore

    private lateinit var httpServer: HttpServer
    private lateinit var endpoint: DefaultInstanceEndpoint
    private val hits = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun setUp() {
        hits.clear()
        httpServer = HttpServer.create(InetSocketAddress(0), 0)
        httpServer.start()
        endpoint = DefaultInstanceEndpoint(instanceRepository, store)
        endpoint.pointAt("http://localhost:${httpServer.address.port}")
    }

    @AfterEach
    fun tearDown() {
        httpServer.stop(0)
        endpoint.restore()
    }

    /** Serves a repository and its sub-resources; [failing] makes the issues call return 500 */
    private fun serve(owner: String, name: String, failing: Boolean = false) {
        for (sub in listOf("", "/issues", "/pulls", "/releases")) {
            val path = "/repos/$owner/$name$sub"
            try {
                httpServer.removeContext(path)
            } catch (_: IllegalArgumentException) {}
            httpServer.createContext(path) { exchange ->
                if (sub.isEmpty()) hits += name
                if (failing && sub == "/issues") {
                    exchange.sendResponseHeaders(500, -1)
                    return@createContext
                }
                val body =
                    if (sub.isEmpty())
                        """{"id": 1, "full_name": "$owner/$name", "default_branch": "main"}"""
                    else "[]"
                exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray()) }
            }
        }
    }

    private fun repo(name: String, dueAt: Instant, failing: Boolean = false): GitHubRepo {
        serve("owner", name, failing)
        return repoRepository.save(
            GitHubRepo(
                instance = store.defaultInstance(),
                owner = "owner",
                name = name,
                nextPollAt = dueAt,
                lastPolledAt = dueAt.minusSeconds(3600),
            )
        )
    }

    @Test
    fun `a tick polls only the batch size, oldest due first, and advances next poll time`() {
        val now = Instant.now()
        repo("c", now.minusSeconds(10))
        repo("a", now.minusSeconds(3000))
        repo("b", now.minusSeconds(2000))
        repo("later", now.plusSeconds(600))

        assertEquals(2, pollingService.pollDue(now))
        assertEquals(listOf("a", "b"), hits.distinct())
        val a =
            repoRepository.findByInstanceAndOwnerAndName(store.defaultInstance(), "owner", "a")!!
        assertTrue(a.nextPollAt.isAfter(now.plus(Duration.ofMinutes(59))), a.nextPollAt.toString())
        assertTrue(
            a.lastPolledAt != null && !a.lastPolledAt!!.isBefore(now),
            a.lastPolledAt.toString(),
        )

        assertEquals(1, pollingService.pollDue(now.plusSeconds(90)))
        assertEquals(listOf("a", "b", "c"), hits.distinct())
        assertEquals(0, pollingService.pollDue(now.plusSeconds(180)))
    }

    @Test
    fun `a repository whose API fails backs off and recovers, and webhook repositories are not polled`() {
        val now = Instant.now()
        val dead = repo("dead", now.minusSeconds(1), failing = true)
        repoRepository.save(
            GitHubRepo(
                instance = store.defaultInstance(),
                owner = "owner",
                name = "hooked",
                deliveryMode = dev.streampack.forge.model.DeliveryMode.WEBHOOK,
                nextPollAt = now.minusSeconds(1),
            )
        )

        assertEquals(1, pollingService.pollDue(now))
        var current = repoRepository.findById(dead.id).get()
        assertEquals(1, current.pollFailures)
        assertEquals(now.plus(Duration.ofHours(1)).epochSecond, current.nextPollAt.epochSecond)

        pollingService.pollDue(current.nextPollAt)
        current = repoRepository.findById(dead.id).get()
        assertEquals(2, current.pollFailures)
        assertEquals(
            Duration.ofHours(2),
            Duration.between(now.plus(Duration.ofHours(1)), current.nextPollAt).let {
                Duration.ofSeconds(it.seconds)
            },
        )

        serve("owner", "dead")
        val at = current.nextPollAt
        pollingService.pollDue(at)
        current = repoRepository.findById(dead.id).get()
        assertEquals(0, current.pollFailures)
        assertTrue(
            current.nextPollAt.isAfter(at.plus(Duration.ofMinutes(59))),
            current.nextPollAt.toString(),
        )
        assertTrue("hooked" !in hits, hits.toString())
    }

    @Test
    fun `a newly added repository is scheduled one interval out`() {
        serve("owner", "fresh")
        val admin =
            UserPrincipal(
                id = UUID.randomUUID(),
                username = "admin",
                displayName = "Admin",
                role = Role.ADMIN,
            )
        val message =
            MessageBuilder.withPayload("github add owner/fresh")
                .setHeader(
                    Provenance.HEADER,
                    Provenance(
                        protocol = Protocol.CONSOLE,
                        serviceId = "",
                        replyTo = "local",
                        user = admin,
                    ),
                )
                .build()
        val before = Instant.now()
        assertInstanceOf(OperationResult.Success::class.java, eventGateway.process(message))
        val fresh =
            repoRepository.findByInstanceAndOwnerAndName(
                store.defaultInstance(),
                "owner",
                "fresh",
            )!!
        assertTrue(
            fresh.nextPollAt.isAfter(before.plus(Duration.ofMinutes(59))),
            fresh.nextPollAt.toString(),
        )
        assertEquals(0, pollingService.pollDue(Instant.now()))
    }
}
