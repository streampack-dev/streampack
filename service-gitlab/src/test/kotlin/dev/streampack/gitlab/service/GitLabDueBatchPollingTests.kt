/* Joseph B. Ottinger (C)2026 */
package dev.streampack.gitlab.service

import com.sun.net.httpserver.HttpServer
import dev.streampack.gitlab.entity.GitLabProject
import dev.streampack.gitlab.repository.GitLabInstanceRepository
import dev.streampack.gitlab.repository.GitLabProjectRepository
import dev.streampack.test.ResetDatabaseBeforeEach
import dev.streampack.test.TestSecurityConfiguration
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/** GitLab projects are polled in bounded batches, oldest due first, with backoff (#69). */
@SpringBootTest(
    properties =
        [
            "streampack.gitlab.batch-size=2",
            "streampack.gitlab.poll-interval=PT1H",
            "streampack.gitlab.max-backoff=PT4H",
        ]
)
@ResetDatabaseBeforeEach
@Import(TestSecurityConfiguration::class)
@ResourceLock("gitlab-api-endpoint")
class GitLabDueBatchPollingTests {
    @Autowired lateinit var pollingService: GitLabPollingService
    @Autowired lateinit var subscriptionService: GitLabSubscriptionService
    @Autowired lateinit var projectRepository: GitLabProjectRepository
    @Autowired lateinit var instanceRepository: GitLabInstanceRepository
    @Autowired lateinit var store: GitLabForgeStore

    private lateinit var httpServer: HttpServer
    private val hits = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun setUp() {
        hits.clear()
        httpServer = HttpServer.create(InetSocketAddress(0), 0)
        httpServer.start()
        instanceRepository.save(
            store
                .defaultInstance()
                .copy(apiUrl = "http://localhost:${httpServer.address.port}/api/v4")
        )
    }

    @AfterEach fun tearDown() = httpServer.stop(0)

    private fun serve(path: String, failing: Boolean = false) {
        val root = "/api/v4/projects/$path"
        try {
            httpServer.removeContext(root)
        } catch (_: IllegalArgumentException) {}
        httpServer.createContext(root) { exchange ->
            val sub = exchange.requestURI.path.removePrefix(root)
            if (sub == "/issues") hits += path
            if (failing && sub == "/issues") {
                exchange.sendResponseHeaders(500, -1)
                return@createContext
            }
            val body =
                if (sub.isEmpty())
                    """{"id": 1, "path_with_namespace": "$path", "default_branch": "main"}"""
                else "[]"
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
    }

    private fun project(path: String, dueAt: Instant, failing: Boolean = false): GitLabProject {
        serve(path, failing)
        return projectRepository.save(
            GitLabProject(
                instance = store.defaultInstance(),
                fullPath = path,
                projectId = path.hashCode().toLong(),
                nextPollAt = dueAt,
                lastPolledAt = dueAt.minusSeconds(3600),
            )
        )
    }

    @Test
    fun `a tick polls only the batch size, oldest due first, and advances next poll time`() {
        val now = Instant.now()
        project("g/c", now.minusSeconds(10))
        project("g/a", now.minusSeconds(3000))
        project("g/b", now.minusSeconds(2000))
        project("g/later", now.plusSeconds(600))

        assertEquals(2, pollingService.pollDue(now))
        assertEquals(listOf("g/a", "g/b"), hits.toList())
        val a = projectRepository.findByInstanceAndFullPath(store.defaultInstance(), "g/a")!!
        assertTrue(a.nextPollAt.isAfter(now.plus(Duration.ofMinutes(59))), a.nextPollAt.toString())
        assertEquals(1, pollingService.pollDue(now.plusSeconds(90)))
        assertEquals(0, pollingService.pollDue(now.plusSeconds(180)))
    }

    @Test
    fun `a project whose API fails backs off and recovers`() {
        val now = Instant.now()
        val dead = project("g/dead", now.minusSeconds(1), failing = true)
        pollingService.pollDue(now)
        var current = projectRepository.findById(dead.id).get()
        assertEquals(1, current.pollFailures)
        assertEquals(now.plus(Duration.ofHours(1)).epochSecond, current.nextPollAt.epochSecond)
        pollingService.pollDue(current.nextPollAt)
        current = projectRepository.findById(dead.id).get()
        assertEquals(2, current.pollFailures)

        serve("g/dead")
        val at = current.nextPollAt
        pollingService.pollDue(at)
        current = projectRepository.findById(dead.id).get()
        assertEquals(0, current.pollFailures)
        assertTrue(
            current.nextPollAt.isAfter(at.plus(Duration.ofMinutes(59))),
            current.nextPollAt.toString(),
        )
    }

    @Test
    fun `a newly added project is scheduled one interval out`() {
        serve("g/fresh")
        val before = Instant.now()
        subscriptionService.addProject(store.defaultInstance(), "g/fresh", null)
        val fresh =
            projectRepository.findByInstanceAndFullPath(store.defaultInstance(), "g/fresh")!!
        assertTrue(
            fresh.nextPollAt.isAfter(before.plus(Duration.ofMinutes(59))),
            fresh.nextPollAt.toString(),
        )
        assertEquals(0, pollingService.pollDue(Instant.now()))
    }
}
