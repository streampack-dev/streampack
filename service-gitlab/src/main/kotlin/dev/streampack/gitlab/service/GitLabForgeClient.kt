/* Joseph B. Ottinger (C)2026 */
package dev.streampack.gitlab.service

import dev.streampack.forge.ForgeKind
import dev.streampack.forge.client.ForgeClient
import dev.streampack.forge.client.WebhookEnvelope
import dev.streampack.forge.model.ForgeEvent
import dev.streampack.forge.model.ForgeInstance
import dev.streampack.forge.model.ForgeItem
import dev.streampack.forge.model.ForgeProjectRef
import dev.streampack.forge.model.ForgeReleaseInfo
import dev.streampack.gitlab.config.ConditionalOnGitLab
import java.security.MessageDigest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode

/**
 * GitLab's [ForgeClient]: REST v4 access through [GitLabApiClient] and the GitLab webhook contract.
 *
 * GitLab does not sign payloads. It sends the configured secret token verbatim in `X-Gitlab-Token`,
 * so verification is a constant-time comparison with the stored secret and the route must sit
 * behind HTTPS. `X-Gitlab-Event` names the hook kind; `X-Gitlab-Event-UUID` identifies a delivery
 * for deduplication.
 */
@Service
@ConditionalOnGitLab
class GitLabForgeClient(private val apiClient: GitLabApiClient) : ForgeClient {
    private val logger = LoggerFactory.getLogger(GitLabForgeClient::class.java)

    override val kind: ForgeKind = ForgeKind.GITLAB

    override fun lookupProject(
        instance: ForgeInstance,
        path: String,
        token: String?,
    ): ForgeProjectRef? {
        if (!isValidPath(path)) return null
        return apiClient.lookupProject(instance.apiUrl, path, token)
    }

    override fun fetchIssuesSince(
        instance: ForgeInstance,
        path: String,
        token: String?,
        sinceNumber: Int,
    ): List<ForgeItem> = apiClient.fetchIssues(instance.apiUrl, path, token, sinceNumber)

    override fun fetchChangeRequestsSince(
        instance: ForgeInstance,
        path: String,
        token: String?,
        sinceNumber: Int,
    ): List<ForgeItem> = apiClient.fetchMergeRequests(instance.apiUrl, path, token, sinceNumber)

    override fun fetchReleases(
        instance: ForgeInstance,
        path: String,
        token: String?,
    ): List<ForgeReleaseInfo> = apiClient.fetchReleases(instance.apiUrl, path, token)

    override fun webhookEnvelope(header: (String) -> String?): WebhookEnvelope? {
        val token = header(TOKEN_HEADER)
        val event = header(EVENT_HEADER)
        if (token.isNullOrBlank() || event.isNullOrBlank()) return null
        return WebhookEnvelope(
            event = event,
            deliveryId = header(DELIVERY_HEADER),
            signature = token,
        )
    }

    override fun isSupportedWebhookEvent(envelope: WebhookEnvelope): Boolean =
        envelope.event in supportedEvents

    override fun webhookProjectRef(root: JsonNode): ForgeProjectRef? {
        val project = root.path("project")
        val path = project.path("path_with_namespace").asString("")
        val id = project.path("id").asLong(0)
        if (path.isBlank() && id <= 0) return null
        return ForgeProjectRef(path = path, externalId = if (id > 0) id.toString() else null)
    }

    override fun verifyWebhook(
        envelope: WebhookEnvelope,
        secret: String,
        body: ByteArray,
    ): Boolean {
        val presented = envelope.signature ?: return false
        return MessageDigest.isEqual(
            presented.toByteArray(Charsets.UTF_8),
            secret.toByteArray(Charsets.UTF_8),
        )
    }

    override fun parseWebhookEvent(envelope: WebhookEnvelope, root: JsonNode): ForgeEvent? {
        val path = root.path("project").path("path_with_namespace").asString("")
        return when (envelope.event) {
            ISSUE_HOOK -> openedItem(root, "issue", path)?.let { ForgeEvent.IssueOpened(it) }
            MERGE_REQUEST_HOOK ->
                openedItem(root, "merge request", path)?.let { ForgeEvent.ChangeRequestOpened(it) }
            RELEASE_HOOK -> {
                val action = root.path("action").asString("")
                if (action != "create") {
                    ignored("release", path, action)
                    return null
                }
                ForgeEvent.ReleasePublished(
                    ForgeReleaseInfo(
                        tag = root.path("tag").asString(""),
                        name = root.path("name").asString(null),
                        url = root.path("url").asString(""),
                    )
                )
            }
            else -> null
        }
    }

    /** `object_attributes` of an issue or merge request hook, when its action is `open` */
    private fun openedItem(root: JsonNode, what: String, path: String): ForgeItem? {
        val attributes = root.path("object_attributes")
        val action = attributes.path("action").asString("")
        if (action != "open") {
            ignored(what, path, action)
            return null
        }
        return ForgeItem(
            number = attributes.path("iid").asInt(0),
            title = attributes.path("title").asString(""),
            url = attributes.path("url").asString(""),
        )
    }

    private fun ignored(what: String, path: String, action: String) {
        logger.debug(
            "Ignoring GitLab {} webhook for {} with unsupported action '{}'",
            what,
            path,
            action,
        )
    }

    companion object {
        const val TOKEN_HEADER = "X-Gitlab-Token"
        const val EVENT_HEADER = "X-Gitlab-Event"
        const val DELIVERY_HEADER = "X-Gitlab-Event-UUID"
        const val ISSUE_HOOK = "Issue Hook"
        const val MERGE_REQUEST_HOOK = "Merge Request Hook"
        const val RELEASE_HOOK = "Release Hook"
        private val supportedEvents = setOf(ISSUE_HOOK, MERGE_REQUEST_HOOK, RELEASE_HOOK)

        /** A full path is at least `namespace/project`, with no empty segments */
        fun isValidPath(path: String): Boolean {
            val segments = path.split("/")
            return segments.size >= 2 && segments.all { it.isNotBlank() }
        }
    }
}
