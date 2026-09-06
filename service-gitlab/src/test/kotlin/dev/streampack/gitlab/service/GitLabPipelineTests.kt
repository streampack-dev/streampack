/* Joseph B. Ottinger (C)2026 */
package dev.streampack.gitlab.service

import com.sun.net.httpserver.HttpServer
import dev.streampack.core.integration.EgressSubscriber
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.forge.model.DeliveryMode
import dev.streampack.forge.subscription.PipelineFilter
import dev.streampack.gitlab.entity.GitLabProject
import dev.streampack.gitlab.repository.GitLabInstanceRepository
import dev.streampack.gitlab.repository.GitLabProjectRepository
import dev.streampack.test.ResetDatabaseBeforeEach
import dev.streampack.test.TestSecurityConfiguration
import java.net.InetSocketAddress
import java.time.Instant
import java.time.format.DateTimeFormatter
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
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.messaging.SubscribableChannel
import org.springframework.messaging.support.MessageBuilder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

/**
 * Pipeline outcomes on GitLab: a named working branch, MR pipelines, polling, and the Pipeline
 * Hook.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ResetDatabaseBeforeEach
@Import(TestSecurityConfiguration::class)
@ResourceLock("gitlab-api-endpoint")
class GitLabPipelineTests {

    @TestConfiguration
    class CapturingConfig {
        @Bean
        fun capturingPipelineSubscriber(
            @Qualifier("egressChannel") egressChannel: SubscribableChannel
        ): Capturing {
            val subscriber = Capturing()
            egressChannel.subscribe(subscriber)
            return subscriber
        }
    }

    class Capturing : EgressSubscriber() {
        val captured = CopyOnWriteArrayList<Pair<String, String>>()

        override fun matches(provenance: Provenance): Boolean = true

        override fun deliver(result: OperationResult, provenance: Provenance) {
            captured.add(
                (result as OperationResult.Success).payload.toString() to provenance.encode()
            )
        }
    }

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var pollingService: GitLabPollingService
    @Autowired lateinit var subscriptionService: GitLabSubscriptionService
    @Autowired lateinit var projectRepository: GitLabProjectRepository
    @Autowired lateinit var instanceRepository: GitLabInstanceRepository
    @Autowired lateinit var store: GitLabForgeStore
    @Autowired lateinit var cipher: GitLabWebhookSecretCipher
    @Autowired lateinit var capturing: Capturing

    private lateinit var httpServer: HttpServer
    private val secret = "hook-token"
    private val channel =
        Provenance(protocol = Protocol.IRC, serviceId = "libera", replyTo = "#dev").encode()
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
                    protocol = Protocol.IRC,
                    serviceId = "libera",
                    replyTo = "#dev",
                    user = adminUser,
                ),
            )
            .build()

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
        stub(
            "/api/v4/projects/group/project",
            """{"id": 100, "path_with_namespace": "group/project", "default_branch": "main", "web_url": "https://gitlab.com/group/project"}""",
        )
        for (sub in listOf("issues", "merge_requests", "releases")) stub(
            "/api/v4/projects/group/project/$sub",
            "[]",
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

    private fun iso(instant: Instant) = DateTimeFormatter.ISO_INSTANT.format(instant)

    private fun pipeline(
        id: Long,
        ref: String,
        status: String,
        source: String = "push",
        updatedAt: Instant = Instant.now(),
    ) =
        """{"id": $id, "iid": $id, "project_id": 100, "ref": "$ref", "sha": "abc", "status": "$status", "source": "$source",
            "web_url": "https://gitlab.com/group/project/-/pipelines/$id", "created_at": "${iso(updatedAt.minusSeconds(300))}", "updated_at": "${iso(updatedAt)}"}"""

    private fun seedProject(): GitLabProject =
        projectRepository.save(
            GitLabProject(
                instance = store.defaultInstance(),
                fullPath = "group/project",
                projectId = 100,
                lastPolledAt = Instant.now().minusSeconds(3600),
                deliveryMode = DeliveryMode.WEBHOOK,
                webhookSecret = cipher.encrypt(secret),
            )
        )

    @Test
    fun `polling reports the development branch and MR pipelines with stage-qualified failed jobs`() {
        val project = seedProject()
        subscriptionService.subscribe(
            store.defaultInstance(),
            "group/project",
            channel,
            listOf(
                PipelineFilter.parse("pipelines:branch:development")!!,
                PipelineFilter.parse("pipelines:failed")!!,
            ),
        )
        stub(
            "/api/v4/projects/group/project/pipelines",
            "[" +
                listOf(
                        pipeline(1, "development", "failed"),
                        pipeline(2, "main", "failed"),
                        pipeline(
                            3,
                            "refs/merge-requests/45/head",
                            "failed",
                            source = "merge_request_event",
                        ),
                        pipeline(
                            4,
                            "refs/merge-requests/46/head",
                            "success",
                            source = "merge_request_event",
                        ),
                        pipeline(5, "development", "running"),
                        pipeline(
                            6,
                            "development",
                            "success",
                            updatedAt = Instant.now().minusSeconds(7200),
                        ),
                    )
                    .joinToString(",") +
                "]",
        )
        stub("/api/v4/projects/group/project/pipelines/1", """{"id": 1, "duration": 252}""")
        stub(
            "/api/v4/projects/group/project/pipelines/1/jobs",
            """[
            {"id": 11, "name": "unit-tests", "stage": "test", "status": "failed", "allow_failure": false},
            {"id": 12, "name": "flaky", "stage": "test", "status": "failed", "allow_failure": true},
            {"id": 13, "name": "unit-tests", "stage": "test", "status": "failed", "allow_failure": false},
            {"id": 10, "name": "ktfmt", "stage": "lint", "status": "failed", "allow_failure": false}]""",
        )
        stub("/api/v4/projects/group/project/pipelines/3", """{"id": 3, "duration": 30}""")
        stub(
            "/api/v4/projects/group/project/pipelines/3/jobs",
            """[{"id": 31, "name": "build", "stage": "build", "status": "failed", "allow_failure": false}]""",
        )
        for (id in listOf(2, 4, 6)) stub(
            "/api/v4/projects/group/project/pipelines/$id",
            """{"id": $id, "duration": 10}""",
        )

        pollingService.pollProject(project.id.toString())

        val texts = capturing.captured.map { it.first }.sorted()
        assertEquals(
            listOf(
                "[group/project] MR !45 pipeline FAILED (build: build) - https://gitlab.com/group/project/-/pipelines/3",
                "[group/project] development pipeline FAILED (test: unit-tests, lint: ktfmt) - https://gitlab.com/group/project/-/pipelines/1",
            ),
            texts,
        )

        capturing.captured.clear()
        pollingService.pollProject(project.id.toString())
        assertEquals(0, capturing.captured.size, capturing.captured.toString())
    }

    @Test
    fun `pipeline hook carries the merge request and jobs inline and is not repeated by polling`() {
        val project = seedProject()
        eventGateway.process(adminMessage("gitlab subscribe group/project pipelines"))
        capturing.captured.clear()
        val hook =
            """{"object_kind": "pipeline",
                "object_attributes": {"id": 9, "iid": 9, "ref": "feature", "source": "merge_request_event", "status": "success", "duration": 125,
                    "created_at": "2026-09-06 10:00:00 UTC", "finished_at": "2026-09-06 10:02:05 UTC"},
                "merge_request": {"iid": 7, "title": "Do it", "url": "https://gitlab.com/group/project/-/merge_requests/7"},
                "project": {"id": 100, "path_with_namespace": "group/project", "default_branch": "main", "web_url": "https://gitlab.com/group/project"},
                "builds": [{"id": 91, "stage": "test", "name": "unit", "status": "success", "allow_failure": false}]}"""
        mockMvc
            .post("/webhooks/gitlab") {
                contentType = MediaType.APPLICATION_JSON
                content = hook
                header("X-Gitlab-Event", "Pipeline Hook")
                header("X-Gitlab-Token", secret)
            }
            .andExpect { status { isAccepted() } }
        assertEquals(
            listOf(
                "[group/project] MR !7 pipeline succeeded (2m05s) - https://gitlab.com/group/project/-/pipelines/9" to
                    channel
            ),
            capturing.captured.toList(),
        )

        val failedHook =
            hook
                .replace("\"id\": 9, \"iid\": 9", "\"id\": 10, \"iid\": 10")
                .replace("\"status\": \"success\"", "\"status\": \"failed\"")
                .replace("\"iid\": 7", "\"iid\": 8")
                .replace(
                    """{"id": 91, "stage": "test", "name": "unit", "status": "success", "allow_failure": false}""",
                    """{"id": 92, "stage": "test", "name": "unit", "status": "failed", "allow_failure": false}, {"id": 93, "stage": "deploy", "name": "optional", "status": "failed", "allow_failure": true}""",
                )
        mockMvc
            .post("/webhooks/gitlab") {
                contentType = MediaType.APPLICATION_JSON
                content = failedHook
                header("X-Gitlab-Event", "Pipeline Hook")
                header("X-Gitlab-Token", secret)
            }
            .andExpect { status { isAccepted() } }
        assertEquals(
            "[group/project] MR !8 pipeline FAILED (test: unit) - https://gitlab.com/group/project/-/pipelines/10",
            capturing.captured[1].first,
        )

        stub(
            "/api/v4/projects/group/project/pipelines",
            "[" +
                pipeline(
                    9,
                    "refs/merge-requests/7/head",
                    "success",
                    source = "merge_request_event",
                ) +
                "," +
                pipeline(
                    10,
                    "refs/merge-requests/8/head",
                    "failed",
                    source = "merge_request_event",
                ) +
                "]",
        )
        pollingService.pollProject(project.id.toString())
        assertEquals(2, capturing.captured.size, capturing.captured.toString())

        val listed = eventGateway.process(adminMessage("gitlab subscriptions"))
        assertTrue(
            assertInstanceOf(OperationResult.Success::class.java, listed)
                .payload
                .toString()
                .contains("group/project [pipelines]")
        )
    }
}
