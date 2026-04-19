/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.controller

import dev.streampack.core.json.JacksonMappers
import dev.streampack.github.model.DeliveryMode
import dev.streampack.github.model.GitHubIssueEvent
import dev.streampack.github.model.GitHubPullRequestEvent
import dev.streampack.github.model.GitHubReleaseEvent
import dev.streampack.github.repository.GitHubRepoRepository
import dev.streampack.github.service.GitHubWebhookDeliveryTracker
import dev.streampack.github.service.GitHubWebhookService
import dev.streampack.github.service.WebhookSecretCipher
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.servlet.http.HttpServletRequest
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.DigestException
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode

@RestController
@RequestMapping("/webhooks/github")
class GitHubWebhookController(
    private val repoRepository: GitHubRepoRepository,
    private val secretCipher: WebhookSecretCipher,
    private val webhookService: GitHubWebhookService,
    private val deliveryTracker: GitHubWebhookDeliveryTracker,
) {
    private val objectMapper = JacksonMappers.standard()
    private val logger = LoggerFactory.getLogger(GitHubWebhookController::class.java)

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
        if (signature.isNullOrBlank() || event.isNullOrBlank()) {
            logger.warn(
                "Rejecting GitHub webhook delivery {} because required headers are missing (eventPresent={}, signaturePresent={})",
                deliveryId ?: "unknown",
                !event.isNullOrBlank(),
                !signature.isNullOrBlank(),
            )
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build()
        }
        val body = request.inputStream.readAllBytes()
        logger.debug(
            "Received GitHub webhook delivery {} (event={}, contentType={}, bodyBytes={})",
            deliveryId ?: "unknown",
            event,
            contentType ?: "unknown",
            body.size,
        )
        if (event !in supportedEvents) {
            logger.warn(
                "Ignoring unsupported GitHub event '{}' (deliveryId={})",
                event,
                deliveryId ?: "unknown",
            )
            return ResponseEntity.status(HttpStatus.ACCEPTED).build()
        }

        val payload =
            extractJsonPayload(body, contentType)
                ?: run {
                    logger.warn(
                        "Rejecting GitHub webhook delivery {} because form payload field is missing (event={}, contentType={})",
                        deliveryId ?: "unknown",
                        event,
                        contentType ?: "unknown",
                    )
                    return ResponseEntity.badRequest().build()
                }
        val root: JsonNode =
            try {
                objectMapper.readTree(payload)
            } catch (ex: Exception) {
                logger.warn("Failed to parse GitHub webhook payload: {}", ex.message)
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).build()
            }

        val fullName = root.path("repository").path("full_name").asString()
        if (fullName.isBlank()) {
            logger.warn(
                "Rejecting GitHub webhook delivery {} because repository.full_name is missing (event={})",
                deliveryId ?: "unknown",
                event,
            )
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build()
        }
        val parts = fullName.split("/")
        if (parts.size != 2) {
            logger.warn(
                "Rejecting GitHub webhook delivery {} because repository.full_name is invalid: {}",
                deliveryId ?: "unknown",
                fullName,
            )
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build()
        }
        val repo =
            repoRepository.findByOwnerAndName(parts[0], parts[1])
                ?: run {
                    logger.warn(
                        "Ignoring GitHub webhook delivery for unknown repository {} (deliveryId={}, event={})",
                        fullName,
                        deliveryId ?: "unknown",
                        event,
                    )
                    return ResponseEntity.status(HttpStatus.ACCEPTED).build()
                }
        if (repo.deliveryMode != DeliveryMode.WEBHOOK || repo.webhookSecret.isNullOrBlank()) {
            logger.warn(
                "Ignoring GitHub webhook delivery for {} because repository is not webhook-enabled (deliveryId={}, event={}, deliveryMode={}, hasSecret={})",
                fullName,
                deliveryId ?: "unknown",
                event,
                repo.deliveryMode,
                !repo.webhookSecret.isNullOrBlank(),
            )
            return ResponseEntity.status(HttpStatus.ACCEPTED).build()
        }

        val secret =
            try {
                secretCipher.decrypt(repo.webhookSecret!!)
            } catch (ex: Exception) {
                logger.warn("Failed to decrypt webhook secret for {}: {}", fullName, ex.message)
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build()
            }

        if (!verifySignature(signature, secret, body)) {
            logger.warn(
                "Invalid GitHub webhook signature for {} (deliveryId={}, event={}, contentType={}, bodyBytes={}, bodySha256={})",
                fullName,
                deliveryId ?: "unknown",
                event,
                contentType ?: "unknown",
                body.size,
                sha256Hex(body).take(16),
            )
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
        if (!deliveryId.isNullOrBlank() && deliveryTracker.isDuplicate(deliveryId)) {
            logger.warn(
                "Ignoring duplicate GitHub webhook delivery {} for {} ({})",
                deliveryId,
                fullName,
                event,
            )
            return ResponseEntity.status(HttpStatus.ACCEPTED).build()
        }

        when (event) {
            "issues" -> {
                val issueEvent = objectMapper.treeToValue(root, GitHubIssueEvent::class.java)
                webhookService.handleIssue(repo, issueEvent)
            }
            "pull_request" -> {
                val prEvent = objectMapper.treeToValue(root, GitHubPullRequestEvent::class.java)
                webhookService.handlePullRequest(repo, prEvent)
            }
            "release" -> {
                val releaseEvent = objectMapper.treeToValue(root, GitHubReleaseEvent::class.java)
                webhookService.handleRelease(repo, releaseEvent)
            }
            "ping" -> {
                logger.info("Received GitHub webhook ping for {}", fullName)
                webhookService.handlePing(repo, root.path("zen").asString(null))
            }
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).build()
    }

    private fun extractJsonPayload(body: ByteArray, contentType: String?): ByteArray? {
        if (contentType?.startsWith(MediaType.APPLICATION_FORM_URLENCODED_VALUE) != true) {
            return body
        }
        val form = body.toString(StandardCharsets.UTF_8)
        val payloadField =
            form
                .split("&")
                .mapNotNull { token ->
                    val pair = token.split("=", limit = 2)
                    val key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8)
                    val value = pair.getOrNull(1) ?: ""
                    if (key == "payload") value else null
                }
                .firstOrNull() ?: return null
        val decoded = URLDecoder.decode(payloadField, StandardCharsets.UTF_8)
        return decoded.toByteArray(StandardCharsets.UTF_8)
    }

    private fun verifySignature(header: String, secret: String, body: ByteArray): Boolean {
        if (!header.startsWith("sha256=")) return false
        val expectedHex = header.removePrefix("sha256=")
        if (expectedHex.length != 64) return false
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val actualBytes = mac.doFinal(body)
        val expectedBytes =
            try {
                hexToBytes(expectedHex)
            } catch (_: DigestException) {
                return false
            }
        if (expectedBytes.size != actualBytes.size) return false
        return MessageDigest.isEqual(expectedBytes, actualBytes)
    }

    private fun hexToBytes(input: String): ByteArray {
        val clean = input.trim()
        if (clean.length % 2 != 0) throw DigestException("Invalid hex length")
        val data = ByteArray(clean.length / 2)
        var i = 0
        while (i < clean.length) {
            val first = Character.digit(clean[i], 16)
            val second = Character.digit(clean[i + 1], 16)
            if (first < 0 || second < 0) throw DigestException("Invalid hex value")
            data[i / 2] = ((first shl 4) + second).toByte()
            i += 2
        }
        return data
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val supportedEvents = setOf("issues", "pull_request", "release", "ping")
    }
}
