/* Joseph B. Ottinger (C)2026 */
package dev.streampack.gitlab.controller

import dev.streampack.core.integration.EgressSubscriber
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import dev.streampack.forge.model.DeliveryMode
import dev.streampack.gitlab.entity.GitLabInstance
import dev.streampack.gitlab.entity.GitLabProject
import dev.streampack.gitlab.entity.GitLabSubscription
import dev.streampack.gitlab.repository.GitLabInstanceRepository
import dev.streampack.gitlab.repository.GitLabProjectRepository
import dev.streampack.gitlab.repository.GitLabSubscriptionRepository
import dev.streampack.gitlab.service.GitLabForgeStore
import dev.streampack.gitlab.service.GitLabWebhookSecretCipher
import dev.streampack.test.ResetDatabaseBeforeEach
import dev.streampack.test.TestSecurityConfiguration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.messaging.SubscribableChannel
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ResetDatabaseBeforeEach
@Import(TestSecurityConfiguration::class)
class GitLabWebhookControllerTests {

    @TestConfiguration
    class CapturingConfig {
        @Bean
        fun capturingSubscriber(
            @Qualifier("egressChannel") egressChannel: SubscribableChannel
        ): CapturingSubscriber {
            val subscriber = CapturingSubscriber()
            egressChannel.subscribe(subscriber)
            return subscriber
        }
    }

    class CapturingSubscriber : EgressSubscriber() {
        val captured = CopyOnWriteArrayList<Pair<OperationResult, Provenance>>()

        override fun matches(provenance: Provenance): Boolean = true

        override fun deliver(result: OperationResult, provenance: Provenance) {
            captured.add(result to provenance)
        }
    }

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var projectRepository: GitLabProjectRepository
    @Autowired lateinit var instanceRepository: GitLabInstanceRepository
    @Autowired lateinit var subscriptionRepository: GitLabSubscriptionRepository
    @Autowired lateinit var store: GitLabForgeStore
    @Autowired lateinit var cipher: GitLabWebhookSecretCipher
    @Autowired lateinit var capturingSubscriber: CapturingSubscriber

    private val secret = "hosted-token"
    private val selfSecret = "self-hosted-token"
    private lateinit var selfHosted: GitLabInstance

    @BeforeEach
    fun seed() {
        capturingSubscriber.captured.clear()
        val hosted =
            projectRepository.save(
                GitLabProject(
                    instance = store.defaultInstance(),
                    fullPath = "group/project",
                    projectId = 100,
                    deliveryMode = DeliveryMode.WEBHOOK,
                    webhookSecret = cipher.encrypt(secret),
                )
            )
        subscriptionRepository.save(
            GitLabSubscription(project = hosted, destinationUri = "console:///local")
        )
        selfHosted =
            instanceRepository.save(
                GitLabInstance(
                    host = "gitlab.example.com",
                    apiUrl = "https://gitlab.example.com/api/v4",
                )
            )
        val self =
            projectRepository.save(
                GitLabProject(
                    instance = selfHosted,
                    fullPath = "team/app",
                    projectId = null,
                    deliveryMode = DeliveryMode.WEBHOOK,
                    webhookSecret = cipher.encrypt(selfSecret),
                )
            )
        subscriptionRepository.save(
            GitLabSubscription(project = self, destinationUri = "console:///self")
        )
    }

    private fun mrPayload(projectId: Long, path: String, iid: Int) =
        """{"object_kind":"merge_request","project":{"id":$projectId,"path_with_namespace":"$path"},
            "object_attributes":{"iid":$iid,"title":"Fix the thing","url":"https://gitlab.com/$path/-/merge_requests/$iid","action":"open"}}"""

    @Test
    fun `matching token fans out an MR to subscribers`() {
        mockMvc
            .post("/webhooks/gitlab") {
                contentType = MediaType.APPLICATION_JSON
                content = mrPayload(100, "group/project", 4)
                header("X-Gitlab-Event", "Merge Request Hook")
                header("X-Gitlab-Event-UUID", "uuid-mr-4")
                header("X-Gitlab-Token", secret)
            }
            .andExpect { status { isAccepted() } }

        assertEquals(1, capturingSubscriber.captured.size)
        val (result, provenance) = capturingSubscriber.captured[0]
        assertEquals("console:///local", provenance.encode())
        val text = (result as OperationResult.Success).payload.toString()
        assertTrue(text.contains("[group/project] New MR #4: Fix the thing"), text)
    }

    @Test
    fun `deliveries are matched on project id so a renamed project still routes`() {
        mockMvc
            .post("/webhooks/gitlab") {
                contentType = MediaType.APPLICATION_JSON
                content = mrPayload(100, "group/renamed-project", 5)
                header("X-Gitlab-Event", "Merge Request Hook")
                header("X-Gitlab-Token", secret)
            }
            .andExpect { status { isAccepted() } }
        assertEquals(1, capturingSubscriber.captured.size)
    }

    @Test
    fun `wrong or missing token is rejected and duplicates are ignored`() {
        mockMvc
            .post("/webhooks/gitlab") {
                contentType = MediaType.APPLICATION_JSON
                content = mrPayload(100, "group/project", 6)
                header("X-Gitlab-Event", "Merge Request Hook")
                header("X-Gitlab-Token", "wrong")
            }
            .andExpect { status { isUnauthorized() } }
        mockMvc
            .post("/webhooks/gitlab") {
                contentType = MediaType.APPLICATION_JSON
                content = mrPayload(100, "group/project", 6)
                header("X-Gitlab-Event", "Merge Request Hook")
            }
            .andExpect { status { isBadRequest() } }
        repeat(2) {
            mockMvc
                .post("/webhooks/gitlab") {
                    contentType = MediaType.APPLICATION_JSON
                    content = mrPayload(100, "group/project", 6)
                    header("X-Gitlab-Event", "Merge Request Hook")
                    header("X-Gitlab-Event-UUID", "uuid-dupe")
                    header("X-Gitlab-Token", secret)
                }
                .andExpect { status { isAccepted() } }
        }
        assertEquals(1, capturingSubscriber.captured.size)
    }

    @Test
    fun `instance route verifies against that instance and falls back to the path when no id is stored`() {
        val issue =
            """{"object_kind":"issue","project":{"id":777,"path_with_namespace":"team/app"},
                "object_attributes":{"iid":1,"title":"Self-hosted issue","url":"https://gitlab.example.com/team/app/-/issues/1","action":"open"}}"""
        mockMvc
            .post("/webhooks/gitlab/${selfHosted.id}") {
                contentType = MediaType.APPLICATION_JSON
                content = issue
                header("X-Gitlab-Event", "Issue Hook")
                header("X-Gitlab-Token", selfSecret)
            }
            .andExpect { status { isAccepted() } }
        assertEquals(1, capturingSubscriber.captured.size)
        val (result, provenance) = capturingSubscriber.captured[0]
        assertEquals("console:///self", provenance.encode())
        assertTrue(
            (result as OperationResult.Success)
                .payload
                .toString()
                .contains("[gitlab.example.com team/app] New issue #1")
        )

        mockMvc
            .post("/webhooks/gitlab/${selfHosted.id}") {
                contentType = MediaType.APPLICATION_JSON
                content = issue
                header("X-Gitlab-Event", "Issue Hook")
                header("X-Gitlab-Token", secret)
            }
            .andExpect { status { isUnauthorized() } }
        mockMvc
            .post("/webhooks/gitlab/${UUID.randomUUID()}") {
                contentType = MediaType.APPLICATION_JSON
                content = issue
                header("X-Gitlab-Event", "Issue Hook")
                header("X-Gitlab-Token", selfSecret)
            }
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `unsupported hooks are accepted and ignored`() {
        mockMvc
            .post("/webhooks/gitlab") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"object_kind":"push","project":{"id":100}}"""
                header("X-Gitlab-Event", "Push Hook")
                header("X-Gitlab-Token", secret)
            }
            .andExpect { status { isAccepted() } }
        assertEquals(0, capturingSubscriber.captured.size)
    }
}
