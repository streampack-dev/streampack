/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.client

import dev.streampack.forge.ForgeKind
import dev.streampack.forge.model.ForgeEvent
import dev.streampack.forge.model.ForgeInstance
import dev.streampack.forge.model.ForgeItem
import dev.streampack.forge.model.ForgePipeline
import dev.streampack.forge.model.ForgeProjectRef
import dev.streampack.forge.model.ForgeReleaseInfo
import java.time.Instant
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

    /**
     * The project at [path] as the forge knows it (canonical path, native id), or null when it does
     * not exist or is not readable with [token].
     */
    fun lookupProject(instance: ForgeInstance, path: String, token: String?): ForgeProjectRef?

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

    /**
     * Pipelines or workflow runs the forge has touched since [since], in any state. Callers apply
     * the settlement gate and the subscription filters; this just fetches.
     */
    fun fetchPipelinesSince(
        instance: ForgeInstance,
        path: String,
        token: String?,
        since: Instant,
    ): List<ForgePipeline>

    /**
     * Display names of the jobs that failed in [pipelineId] and were not allowed to fail, one per
     * job name (the latest attempt wins, so retries are not double-reported).
     */
    fun fetchFailedJobs(
        instance: ForgeInstance,
        path: String,
        token: String?,
        pipelineId: String,
    ): List<String>

    /** The project's default branch, or null when it cannot be read. */
    fun fetchDefaultBranch(instance: ForgeInstance, path: String, token: String?): String?

    /**
     * True when this forge's pipeline webhook payload lists the jobs, so a failed pipeline needs no
     * API call to name them.
     */
    val webhookCarriesFailedJobs: Boolean

    /** Reads the forge's delivery headers; null when a required header is missing. */
    fun webhookEnvelope(header: (String) -> String?): WebhookEnvelope?

    /** Whether the receiver should process this event type at all. */
    fun isSupportedWebhookEvent(envelope: WebhookEnvelope): Boolean

    /** The project named in the payload, or null when absent or malformed. */
    fun webhookProjectRef(root: JsonNode): ForgeProjectRef?

    /** Verifies the delivery against the project's shared secret. */
    fun verifyWebhook(envelope: WebhookEnvelope, secret: String, body: ByteArray): Boolean

    /** Turns a verified payload into an event, or null when the action is not one we report. */
    fun parseWebhookEvent(envelope: WebhookEnvelope, root: JsonNode): ForgeEvent?
}
