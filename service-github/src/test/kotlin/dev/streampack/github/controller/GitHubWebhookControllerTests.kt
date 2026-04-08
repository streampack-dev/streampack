/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.controller

import dev.streampack.core.integration.EgressSubscriber
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import dev.streampack.github.entity.GitHubRepo
import dev.streampack.github.entity.GitHubSubscription
import dev.streampack.github.model.DeliveryMode
import dev.streampack.github.repository.GitHubRepoRepository
import dev.streampack.github.repository.GitHubSubscriptionRepository
import dev.streampack.github.service.WebhookSecretCipher
import dev.streampack.test.ResetDatabaseBeforeEach
import dev.streampack.test.TestSecurityConfiguration
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.text.Charsets
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
class GitHubWebhookControllerTests {

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
    @Autowired lateinit var repoRepository: GitHubRepoRepository
    @Autowired lateinit var subscriptionRepository: GitHubSubscriptionRepository
    @Autowired lateinit var cipher: WebhookSecretCipher
    @Autowired lateinit var capturingSubscriber: CapturingSubscriber

    private val secret = "integration-secret"

    @BeforeEach
    fun seedRepo() {
        capturingSubscriber.captured.clear()
        subscriptionRepository.deleteAll()
        repoRepository.deleteAll()
        val repo =
            repoRepository.save(
                GitHubRepo(
                    owner = "owner",
                    name = "repo",
                    deliveryMode = DeliveryMode.WEBHOOK,
                    webhookSecret = cipher.encrypt(secret),
                )
            )
        subscriptionRepository.save(
            GitHubSubscription(repo = repo, destinationUri = "console:///local")
        )
    }

    @Test
    fun `valid signature returns 202`() {
        val payload =
            """{
            "action":"opened",
            "repository":{"full_name":"owner/repo"},
            "issue":{"number":1,"title":"Test issue","html_url":"https://github.com/owner/repo/issues/1"}
        }"""
        mockMvc
            .post("/webhooks/github") {
                contentType = MediaType.APPLICATION_JSON
                content = payload
                header("X-GitHub-Event", "issues")
                header("X-Hub-Signature-256", sign(payload.toByteArray()))
            }
            .andExpect { status { isAccepted() } }
    }

    @Test
    fun `invalid signature returns 401`() {
        val payload =
            """{
            "action":"opened",
            "repository":{"full_name":"owner/repo"},
            "issue":{"number":1,"title":"Test issue","html_url":"https://github.com/owner/repo/issues/1"}
        }"""
        mockMvc
            .post("/webhooks/github") {
                contentType = MediaType.APPLICATION_JSON
                content = payload
                header("X-GitHub-Event", "issues")
                header("X-Hub-Signature-256", "sha256=badsignature")
            }
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `form encoded payload is accepted`() {
        val jsonPayload =
            """{
            "action":"opened",
            "repository":{"full_name":"owner/repo"},
            "issue":{"number":1,"title":"Test issue","html_url":"https://github.com/owner/repo/issues/1"}
        }"""
        val formBody = "payload=${URLEncoder.encode(jsonPayload, Charsets.UTF_8)}"
        mockMvc
            .post("/webhooks/github") {
                contentType = MediaType.APPLICATION_FORM_URLENCODED
                content = formBody
                header("X-GitHub-Event", "issues")
                header("X-Hub-Signature-256", sign(formBody.toByteArray()))
            }
            .andExpect { status { isAccepted() } }
    }

    @Test
    fun `form encoded payload is accepted when payload is not first field`() {
        val jsonPayload =
            """{
            "action":"opened",
            "repository":{"full_name":"owner/repo"},
            "issue":{"number":1,"title":"Test issue","html_url":"https://github.com/owner/repo/issues/1"}
        }"""
        val formBody = "foo=bar&payload=${URLEncoder.encode(jsonPayload, Charsets.UTF_8)}&empty="
        mockMvc
            .post("/webhooks/github") {
                contentType = MediaType.APPLICATION_FORM_URLENCODED
                content = formBody
                header("X-GitHub-Event", "issues")
                header("X-Hub-Signature-256", sign(formBody.toByteArray()))
            }
            .andExpect { status { isAccepted() } }
    }

    @Test
    fun `form encoded payload with charset suffix is accepted`() {
        val jsonPayload =
            """{
            "action":"opened",
            "repository":{"full_name":"owner/repo"},
            "issue":{"number":1,"title":"Test issue","html_url":"https://github.com/owner/repo/issues/1"}
        }"""
        val formBody = "payload=${URLEncoder.encode(jsonPayload, Charsets.UTF_8)}"
        mockMvc
            .post("/webhooks/github") {
                contentType =
                    MediaType.parseMediaType("application/x-www-form-urlencoded; charset=utf-8")
                content = formBody
                header("X-GitHub-Event", "issues")
                header("X-Hub-Signature-256", sign(formBody.toByteArray()))
            }
            .andExpect { status { isAccepted() } }
    }

    @Test
    fun `form encoded payload missing payload field returns 400`() {
        val formBody = "foo=bar"
        mockMvc
            .post("/webhooks/github") {
                contentType = MediaType.APPLICATION_FORM_URLENCODED
                content = formBody
                header("X-GitHub-Event", "issues")
                header("X-Hub-Signature-256", sign(formBody.toByteArray()))
            }
            .andExpect { status { isBadRequest() } }
    }

    @Test
    fun `ping event is accepted`() {
        val payload =
            """{
            "zen":"Keep it logically awesome.",
            "hook_id":12345,
            "repository":{"full_name":"owner/repo"}
        }"""
        mockMvc
            .post("/webhooks/github") {
                contentType = MediaType.APPLICATION_JSON
                content = payload
                header("X-GitHub-Event", "ping")
                header("X-Hub-Signature-256", sign(payload.toByteArray()))
            }
            .andExpect { status { isAccepted() } }

        assertEquals(1, capturingSubscriber.captured.size)
        val (result, provenance) = capturingSubscriber.captured[0]
        assertEquals("console:///local", provenance.encode())
        assertTrue(result is OperationResult.Success)
        assertTrue(
            (result as OperationResult.Success)
                .payload
                .toString()
                .contains("[owner/repo] Webhook ping received - setup verified.")
        )
    }

    @Test
    fun `ping event with no subscriptions does not fan out`() {
        subscriptionRepository.deleteAll()
        capturingSubscriber.captured.clear()
        val payload =
            """{
            "zen":"Keep it logically awesome.",
            "hook_id":12345,
            "repository":{"full_name":"owner/repo"}
        }"""
        mockMvc
            .post("/webhooks/github") {
                contentType = MediaType.APPLICATION_JSON
                content = payload
                header("X-GitHub-Event", "ping")
                header("X-Hub-Signature-256", sign(payload.toByteArray()))
            }
            .andExpect { status { isAccepted() } }

        assertEquals(0, capturingSubscriber.captured.size)
    }

    @Test
    fun `unsupported event is accepted and ignored`() {
        val payload =
            """{
            "action":"created",
            "repository":{"full_name":"owner/repo"}
        }"""
        mockMvc
            .post("/webhooks/github") {
                contentType = MediaType.APPLICATION_JSON
                content = payload
                header("X-GitHub-Event", "repository_ruleset")
                header("X-Hub-Signature-256", sign(payload.toByteArray()))
            }
            .andExpect { status { isAccepted() } }
    }

    @Test
    fun `duplicate delivery id is accepted`() {
        val payload =
            """{
            "action":"opened",
            "repository":{"full_name":"owner/repo"},
            "issue":{"number":1,"title":"Test issue","html_url":"https://github.com/owner/repo/issues/1"}
        }"""
        val signature = sign(payload.toByteArray())
        repeat(2) {
            mockMvc
                .post("/webhooks/github") {
                    contentType = MediaType.APPLICATION_JSON
                    content = payload
                    header("X-GitHub-Event", "issues")
                    header("X-GitHub-Delivery", "delivery-123")
                    header("X-Hub-Signature-256", signature)
                }
                .andExpect { status { isAccepted() } }
        }
    }

    private fun sign(body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val raw = mac.doFinal(body)
        val hex = raw.joinToString("") { "%02x".format(it) }
        return "sha256=$hex"
    }
}
