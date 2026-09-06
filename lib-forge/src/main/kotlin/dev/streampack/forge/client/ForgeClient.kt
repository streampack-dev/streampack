/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.client

import dev.streampack.forge.ForgeKind
import dev.streampack.forge.model.ForgeEvent
import dev.streampack.forge.model.ForgeInstance
import dev.streampack.forge.model.ForgeItem
import dev.streampack.forge.model.ForgeReleaseInfo
import tools.jackson.databind.JsonNode

/** Headers a forge attaches to a webhook delivery, normalized across forges. */
data class WebhookEnvelope(val event: String, val deliveryId: String?, val signature: String?)

/**
 * Everything that differs between forges: how to talk to the API and how to read a webhook.
 *
 * A project is addressed by its instance and its path within it (`owner/repo`,
 * `group/sub/project`). All persistence, cursoring, formatting, and fan-out live in the shared
 * services.
 */
interface ForgeClient {
    val kind: ForgeKind

    /** True when the project exists and is readable with [token]. */
    fun validateProject(instance: ForgeInstance, path: String, token: String?): Boolean

    /** Issues numbered above [sinceNumber]. */
    fun fetchIssuesSince(
        instance: ForgeInstance,
        path: String,
        token: String?,
        sinceNumber: Int,
    ): List<ForgeItem>

    /** Pull or merge requests numbered above [sinceNumber]. */
    fun fetchChangeRequestsSince(
        instance: ForgeInstance,
        path: String,
        token: String?,
        sinceNumber: Int,
    ): List<ForgeItem>

    /** All releases the API returns. */
    fun fetchReleases(instance: ForgeInstance, path: String, token: String?): List<ForgeReleaseInfo>

    /** Reads the forge's delivery headers; null when a required header is missing. */
    fun webhookEnvelope(header: (String) -> String?): WebhookEnvelope?

    /** Whether the receiver should process this event type at all. */
    fun isSupportedWebhookEvent(envelope: WebhookEnvelope): Boolean

    /** The project path named in the payload, or null when absent or malformed. */
    fun webhookProjectPath(root: JsonNode): String?

    /** Verifies the delivery against the project's shared secret. */
    fun verifyWebhook(envelope: WebhookEnvelope, secret: String, body: ByteArray): Boolean

    /** Turns a verified payload into an event, or null when the action is not one we report. */
    fun parseWebhookEvent(envelope: WebhookEnvelope, root: JsonNode): ForgeEvent?
}
