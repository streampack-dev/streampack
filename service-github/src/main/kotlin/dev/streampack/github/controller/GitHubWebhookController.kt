/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.controller

import dev.streampack.forge.ForgeKind
import dev.streampack.forge.webhook.ForgeWebhookReceiver
import dev.streampack.github.entity.GitHubInstance
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
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Routes for GitHub webhook deliveries; the processing lives in [ForgeWebhookReceiver].
 *
 * The bare route serves github.com, so hooks configured before instances existed keep verifying.
 * Every other instance has its own route, so the receiver never infers the instance from the
 * payload.
 */
@RestController
@RequestMapping("/webhooks/github")
class GitHubWebhookController(
    client: GitHubForgeClient,
    private val store: GitHubForgeStore,
    secretCipher: WebhookSecretCipher,
    webhookService: GitHubWebhookService,
    deliveryTracker: GitHubWebhookDeliveryTracker,
) {
    private val logger = LoggerFactory.getLogger(GitHubWebhookController::class.java)
    private val receiver =
        ForgeWebhookReceiver<GitHubInstance, GitHubRepo, GitHubSubscription>(
            ForgeKind.GITHUB,
            client,
            store,
            secretCipher,
            webhookService,
            deliveryTracker,
        )

    @Operation(
        summary = "Receive GitHub webhook deliveries for github.com repositories",
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
    ): ResponseEntity<Void> =
        receive(store.defaultInstance(), signature, event, deliveryId, contentType, request)

    @Operation(
        summary = "Receive GitHub webhook deliveries for one registered GitHub instance",
        description =
            "Same processing as the bare route, verified against repositories on the instance named by the path.",
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
                ApiResponse(
                    responseCode = "404",
                    description = "No such instance",
                    content = [Content(schema = Schema(hidden = true))],
                ),
            ],
    )
    @PostMapping(
        path = ["/{instanceId}"],
        consumes = [MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_FORM_URLENCODED_VALUE],
    )
    fun receiveForInstance(
        @PathVariable instanceId: String,
        @RequestHeader("X-Hub-Signature-256", required = false) signature: String?,
        @RequestHeader("X-GitHub-Event", required = false) event: String?,
        @RequestHeader("X-GitHub-Delivery", required = false) deliveryId: String?,
        @RequestHeader("Content-Type", required = false) contentType: String?,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        val instance =
            store.findInstanceById(instanceId)
                ?: run {
                    logger.warn(
                        "Rejecting GitHub webhook delivery for unknown instance {}",
                        instanceId,
                    )
                    return ResponseEntity.notFound().build()
                }
        return receive(instance, signature, event, deliveryId, contentType, request)
    }

    private fun receive(
        instance: GitHubInstance,
        signature: String?,
        event: String?,
        deliveryId: String?,
        contentType: String?,
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
        return receiver.receive(
            instance,
            { declared[it] ?: request.getHeader(it) },
            contentType,
            body,
        )
    }
}
