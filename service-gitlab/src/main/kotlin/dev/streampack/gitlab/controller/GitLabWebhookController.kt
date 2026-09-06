/* Joseph B. Ottinger (C)2026 */
package dev.streampack.gitlab.controller

import dev.streampack.forge.ForgeKind
import dev.streampack.forge.webhook.ForgeWebhookReceiver
import dev.streampack.gitlab.config.ConditionalOnGitLab
import dev.streampack.gitlab.entity.GitLabInstance
import dev.streampack.gitlab.entity.GitLabProject
import dev.streampack.gitlab.entity.GitLabSubscription
import dev.streampack.gitlab.service.GitLabForgeClient
import dev.streampack.gitlab.service.GitLabForgeStore
import dev.streampack.gitlab.service.GitLabWebhookDeliveryTracker
import dev.streampack.gitlab.service.GitLabWebhookSecretCipher
import dev.streampack.gitlab.service.GitLabWebhookService
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
 * Routes for GitLab webhook deliveries; the processing lives in [ForgeWebhookReceiver]. The bare
 * route serves gitlab.com; every other instance has its own route, so the receiver never infers the
 * instance from the payload.
 */
@RestController
@ConditionalOnGitLab
@RequestMapping("/webhooks/gitlab")
class GitLabWebhookController(
    client: GitLabForgeClient,
    private val store: GitLabForgeStore,
    secretCipher: GitLabWebhookSecretCipher,
    webhookService: GitLabWebhookService,
    deliveryTracker: GitLabWebhookDeliveryTracker,
) {
    private val logger = LoggerFactory.getLogger(GitLabWebhookController::class.java)
    private val receiver =
        ForgeWebhookReceiver<GitLabInstance, GitLabProject, GitLabSubscription>(
            ForgeKind.GITLAB,
            client,
            store,
            secretCipher,
            webhookService,
            deliveryTracker,
        )

    @Operation(
        summary = "Receive GitLab webhook deliveries for gitlab.com projects",
        description =
            "Compares X-Gitlab-Token with the project's secret token, deduplicates by X-Gitlab-Event-UUID, and fans out issue, merge request, and release hooks.",
        responses =
            [
                ApiResponse(responseCode = "202", description = "Delivery accepted"),
                ApiResponse(
                    responseCode = "400",
                    description = "Malformed payload or project metadata",
                    content = [Content(schema = Schema(hidden = true))],
                ),
                ApiResponse(
                    responseCode = "401",
                    description = "Secret token mismatch",
                    content = [Content(schema = Schema(hidden = true))],
                ),
            ],
    )
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun receive(
        @RequestHeader("X-Gitlab-Token", required = false) token: String?,
        @RequestHeader("X-Gitlab-Event", required = false) event: String?,
        @RequestHeader("X-Gitlab-Event-UUID", required = false) deliveryId: String?,
        @RequestHeader("Content-Type", required = false) contentType: String?,
        request: HttpServletRequest,
    ): ResponseEntity<Void> =
        receive(store.defaultInstance(), token, event, deliveryId, contentType, request)

    @Operation(
        summary = "Receive GitLab webhook deliveries for one registered GitLab instance",
        description =
            "Same processing as the bare route, verified against projects on the instance named by the path.",
        responses =
            [
                ApiResponse(responseCode = "202", description = "Delivery accepted"),
                ApiResponse(
                    responseCode = "400",
                    description = "Malformed payload or project metadata",
                    content = [Content(schema = Schema(hidden = true))],
                ),
                ApiResponse(
                    responseCode = "401",
                    description = "Secret token mismatch",
                    content = [Content(schema = Schema(hidden = true))],
                ),
                ApiResponse(
                    responseCode = "404",
                    description = "No such instance",
                    content = [Content(schema = Schema(hidden = true))],
                ),
            ],
    )
    @PostMapping(path = ["/{instanceId}"], consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun receiveForInstance(
        @PathVariable instanceId: String,
        @RequestHeader("X-Gitlab-Token", required = false) token: String?,
        @RequestHeader("X-Gitlab-Event", required = false) event: String?,
        @RequestHeader("X-Gitlab-Event-UUID", required = false) deliveryId: String?,
        @RequestHeader("Content-Type", required = false) contentType: String?,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        val instance =
            store.findInstanceById(instanceId)
                ?: run {
                    logger.warn(
                        "Rejecting GitLab webhook delivery for unknown instance {}",
                        instanceId,
                    )
                    return ResponseEntity.notFound().build()
                }
        return receive(instance, token, event, deliveryId, contentType, request)
    }

    private fun receive(
        instance: GitLabInstance,
        token: String?,
        event: String?,
        deliveryId: String?,
        contentType: String?,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        /* The named parameters keep the headers in the OpenAPI document; the receiver reads them */
        val declared =
            mapOf(
                GitLabForgeClient.TOKEN_HEADER to token,
                GitLabForgeClient.EVENT_HEADER to event,
                GitLabForgeClient.DELIVERY_HEADER to deliveryId,
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
