/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.controller

import dev.streampack.forge.ForgeKind
import dev.streampack.forge.webhook.ForgeWebhookReceiver
import dev.streampack.github.entity.GitHubRepo
import dev.streampack.github.entity.GitHubSubscription
import dev.streampack.github.service.GitHubForgeClient
import dev.streampack.github.service.GitHubForgeStore
import dev.streampack.github.service.GitHubWebhookDeliveryTracker
import dev.streampack.github.service.GitHubWebhookService
import dev.streampack.github.service.WebhookSecretCipher
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Route for GitHub webhook deliveries; the processing lives in [ForgeWebhookReceiver] */
@RestController
@RequestMapping("/webhooks/github")
class GitHubWebhookController(
    client: GitHubForgeClient,
    store: GitHubForgeStore,
    secretCipher: WebhookSecretCipher,
    webhookService: GitHubWebhookService,
    deliveryTracker: GitHubWebhookDeliveryTracker,
) {
    private val receiver =
        ForgeWebhookReceiver<GitHubRepo, GitHubSubscription>(
            ForgeKind.GITHUB,
            client,
            store,
            secretCipher,
            webhookService,
            deliveryTracker,
        )

    @Operation(
        summary = "Receive GitHub webhook deliveries",
        description =
            "Validates X-Hub-Signature-256, deduplicates deliveries, and fans out supported GitHub events.",
        responses =
            [
                ApiResponse(responseCode = "202", description = "Delivery accepted"),
                ApiResponse(
                    responseCode = "400",
                    description = "Malformed payload or repository metadata",
                    content = [Content(schema = Schema(hidden = true))],
                ),
                ApiResponse(
                    responseCode = "401",
                    description = "Signature mismatch",
                    content = [Content(schema = Schema(hidden = true))],
                ),
            ],
    )
    @PostMapping(
        consumes = [MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_FORM_URLENCODED_VALUE]
    )
    fun receive(
        @RequestHeader("X-Hub-Signature-256", required = false) signature: String?,
        @RequestHeader("X-GitHub-Event", required = false) event: String?,
        @RequestHeader("X-GitHub-Delivery", required = false) deliveryId: String?,
        @RequestHeader("Content-Type", required = false) contentType: String?,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        /* The named parameters keep the headers in the OpenAPI document; the receiver reads them */
        val declared =
            mapOf(
                "X-Hub-Signature-256" to signature,
                "X-GitHub-Event" to event,
                "X-GitHub-Delivery" to deliveryId,
            )
        val body = request.inputStream.readAllBytes()
        return receiver.receive({ declared[it] ?: request.getHeader(it) }, contentType, body)
    }
}
