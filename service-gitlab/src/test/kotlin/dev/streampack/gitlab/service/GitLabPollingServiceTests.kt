/* Joseph B. Ottinger (C)2026 */
package dev.streampack.gitlab.service

import com.sun.net.httpserver.HttpServer
import dev.streampack.core.integration.EgressSubscriber
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.gitlab.entity.GitLabProject
import dev.streampack.gitlab.entity.GitLabSubscription
import dev.streampack.gitlab.repository.GitLabInstanceRepository
import dev.streampack.gitlab.repository.GitLabProjectRepository
import dev.streampack.gitlab.repository.GitLabSubscriptionRepository
import dev.streampack.test.ResetDatabaseBeforeEach
import dev.streampack.test.TestSecurityConfiguration
import java.net.InetSocketAddress
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
@ResetDatabaseBeforeEach
@Import(TestSecurityConfiguration::class)
@ResourceLock("gitlab-api-endpoint")
class GitLabPollingServiceTests {

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
    }

    @Autowired lateinit var pollingService: GitLabPollingService
    @Autowired lateinit var projectRepository: GitLabProjectRepository
    @Autowired lateinit var instanceRepository: GitLabInstanceRepository
    @Autowired lateinit var subscriptionRepository: GitLabSubscriptionRepository
    @Autowired lateinit var store: GitLabForgeStore
    @Autowired lateinit var capturing: CapturingEgressSubscriber

    private lateinit var httpServer: HttpServer

    @BeforeEach
    fun setUp() {
        capturing.captured.clear()
        httpServer = HttpServer.create(InetSocketAddress(0), 0)
        httpServer.start()
        instanceRepository.save(
            store
                .defaultInstance()
                .copy(apiUrl = "http://localhost:${httpServer.address.port}/api/v4")
        )
    }

    @AfterEach fun tearDown() = httpServer.stop(0)

    private fun stub(path: String, body: String) {
        httpServer.createContext(path) { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
    }

    @Test
    fun `polling reports new issues merge requests and releases above the cursors`() {
        val project =
            projectRepository.save(
                GitLabProject(
                    instance = store.defaultInstance(),
                    fullPath = "group/project",
                    projectId = 100,
                    highestIssueNumber = 1,
                    highestMrNumber = 1,
                )
            )
        subscriptionRepository.save(
            GitLabSubscription(
                project = project,
                destinationUri = Provenance(protocol = Protocol.CONSOLE, replyTo = "local").encode(),
            )
        )
        stub(
            "/api/v4/projects/group/project/issues",
            """[{"iid": 2, "title": "Second issue", "web_url": "https://gitlab.com/group/project/-/issues/2"},
                {"iid": 1, "title": "First issue", "web_url": "https://gitlab.com/group/project/-/issues/1"}]""",
        )
        stub(
            "/api/v4/projects/group/project/merge_requests",
            """[{"iid": 3, "title": "Third MR", "web_url": "https://gitlab.com/group/project/-/merge_requests/3"}]""",
        )
        stub(
            "/api/v4/projects/group/project/releases",
            """[{"tag_name": "v1.0.0", "name": "First", "_links": {"self": "https://gitlab.com/group/project/-/releases/v1.0.0"}}]""",
        )

        pollingService.pollProject(project.id.toString())

        val texts =
            capturing.captured.map { (it.first as OperationResult.Success).payload.toString() }
        assertEquals(3, texts.size, texts.toString())
        assertTrue(
            texts.any { it.contains("[group/project] New issue #2: Second issue") },
            texts.toString(),
        )
        assertTrue(
            texts.any { it.contains("[group/project] New MR #3: Third MR") },
            texts.toString(),
        )
        assertTrue(
            texts.any { it.contains("[group/project] New release v1.0.0") },
            texts.toString(),
        )

        val updated = projectRepository.findById(project.id).get()
        assertEquals(2, updated.highestIssueNumber)
        assertEquals(3, updated.highestMrNumber)

        capturing.captured.clear()
        pollingService.pollProject(project.id.toString())
        assertEquals(0, capturing.captured.size)
    }
}
