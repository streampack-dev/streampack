/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.webhook

import dev.streampack.core.json.JacksonMappers
import dev.streampack.forge.ForgeKind
import dev.streampack.forge.client.ForgeClient
import dev.streampack.forge.model.DeliveryMode
import dev.streampack.forge.model.ForgeInstance
import dev.streampack.forge.model.ForgeProject
import dev.streampack.forge.model.ForgeSubscription
import dev.streampack.forge.store.ForgeStore
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import tools.jackson.databind.JsonNode

/**
 * The body of a webhook controller, independent of forge: header check, payload extraction, project
 * lookup, secret verification, deduplication, event parsing, fan-out.
 *
 * A module's controller owns the route, resolves the instance the route addresses (so the instance
 * is never inferred from the payload), and hands the request here. Responses follow the original
 * GitHub contract: 202 for accepted or deliberately ignored deliveries, 400 for malformed input,
 * 401 for a failed signature check, 500 when a stored secret cannot be decrypted.
 */
open class ForgeWebhookReceiver<I : ForgeInstance, P : ForgeProject, S : ForgeSubscription>(
    private val kind: ForgeKind,
    private val client: ForgeClient,
    private val store: ForgeStore<I, P, S>,
    private val secretCipher: SecretCipher,
    private val fanOut: ForgeWebhookFanOut<I, P, S>,
    private val deliveryTracker: WebhookDeliveryTracker,
) {
    private val objectMapper = JacksonMappers.standard()
    private val logger = LoggerFactory.getLogger(javaClass)
    private val name = kind.displayName

    fun receive(
        instance: I,
        header: (String) -> String?,
        contentType: String?,
        body: ByteArray,
    ): ResponseEntity<Void> {
        val envelope =
            client.webhookEnvelope(header)
                ?: run {
                    logger.warn(
                        "Rejecting {} webhook delivery because required headers are missing",
                        name,
                    )
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST).build()
                }
        val deliveryId = envelope.deliveryId ?: "unknown"
        logger.debug(
            "Received {} webhook delivery {} (event={}, contentType={}, bodyBytes={})",
            name,
            deliveryId,
            envelope.event,
            contentType ?: "unknown",
            body.size,
        )
        if (!client.isSupportedWebhookEvent(envelope)) {
            logger.warn(
                "Ignoring unsupported {} event '{}' (deliveryId={})",
                name,
                envelope.event,
                deliveryId,
            )
            return ResponseEntity.status(HttpStatus.ACCEPTED).build()
        }

        val payload =
            extractJsonPayload(body, contentType)
                ?: run {
                    logger.warn(
                        "Rejecting {} webhook delivery {} because form payload field is missing (event={}, contentType={})",
                        name,
                        deliveryId,
                        envelope.event,
                        contentType ?: "unknown",
                    )
                    return ResponseEntity.badRequest().build()
                }
        val root: JsonNode =
            try {
                objectMapper.readTree(payload)
            } catch (ex: Exception) {
                logger.warn("Failed to parse {} webhook payload: {}", name, ex.message)
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).build()
            }

        val ref =
            client.webhookProjectRef(root)
                ?: run {
                    logger.warn(
                        "Rejecting {} webhook delivery {} because the project identifier is missing or invalid (event={})",
                        name,
                        deliveryId,
                        envelope.event,
                    )
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST).build()
                }
        val path = ref.path
        val project =
            store.findProject(instance, ref)
                ?: run {
                    logger.warn(
                        "Ignoring {} webhook delivery for unknown repository {} on {} (deliveryId={}, event={})",
                        name,
                        path,
                        instance.host,
                        deliveryId,
                        envelope.event,
                    )
                    return ResponseEntity.status(HttpStatus.ACCEPTED).build()
                }
        if (project.deliveryMode != DeliveryMode.WEBHOOK || project.webhookSecret.isNullOrBlank()) {
            logger.warn(
                "Ignoring {} webhook delivery for {} because the project is not webhook-enabled (deliveryId={}, event={}, deliveryMode={}, hasSecret={})",
                name,
                path,
                deliveryId,
                envelope.event,
                project.deliveryMode,
                !project.webhookSecret.isNullOrBlank(),
            )
            return ResponseEntity.status(HttpStatus.ACCEPTED).build()
        }

        val secret =
            try {
                secretCipher.decrypt(project.webhookSecret!!)
            } catch (ex: Exception) {
                logger.warn("Failed to decrypt webhook secret for {}: {}", path, ex.message)
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build()
            }

        if (!client.verifyWebhook(envelope, secret, body)) {
            logger.warn(
                "Invalid {} webhook signature for {} (deliveryId={}, event={}, contentType={}, bodyBytes={}, bodySha256={})",
                name,
                path,
                deliveryId,
                envelope.event,
                contentType ?: "unknown",
                body.size,
                sha256Hex(body).take(16),
            )
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
        if (
            !envelope.deliveryId.isNullOrBlank() && deliveryTracker.isDuplicate(envelope.deliveryId)
        ) {
            logger.warn(
                "Ignoring duplicate {} webhook delivery {} for {} ({})",
                name,
                envelope.deliveryId,
                path,
                envelope.event,
            )
            return ResponseEntity.status(HttpStatus.ACCEPTED).build()
        }

        val event = client.parseWebhookEvent(envelope, root)
        if (event == null) {
            logger.debug(
                "Ignoring {} webhook delivery {} for {}: event '{}' carries no reportable action",
                name,
                deliveryId,
                path,
                envelope.event,
            )
        } else {
            fanOut.deliver(project, event)
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).build()
    }

    /** GitHub can deliver form-encoded payloads with the JSON under a `payload` field. */
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

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
