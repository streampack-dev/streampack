/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.service

import dev.streampack.core.json.JacksonMappers
import dev.streampack.forge.ForgeKind
import dev.streampack.forge.client.ForgeClient
import dev.streampack.forge.client.WebhookEnvelope
import dev.streampack.forge.model.ForgeEvent
import dev.streampack.forge.model.ForgeInstance
import dev.streampack.forge.model.ForgeItem
import dev.streampack.forge.model.ForgeProjectRef
import dev.streampack.forge.model.ForgeReleaseInfo
import dev.streampack.github.model.GitHubApiItem
import dev.streampack.github.model.GitHubIssueEvent
import dev.streampack.github.model.GitHubPullRequestEvent
import dev.streampack.github.model.GitHubReleaseEvent
import java.security.DigestException
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode

/**
 * GitHub's [ForgeClient]: REST access through [GitHubApiClient] and the GitHub webhook contract
 * (`X-Hub-Signature-256` HMAC over the body, `X-GitHub-Event` routing, `X-GitHub-Delivery` ids).
 */
@Service
class GitHubForgeClient(private val apiClient: GitHubApiClient) : ForgeClient {
    private val logger = LoggerFactory.getLogger(GitHubForgeClient::class.java)
    private val mapper = JacksonMappers.standard()

    override val kind: ForgeKind = ForgeKind.GITHUB

    override fun lookupProject(
        instance: ForgeInstance,
        path: String,
        token: String?,
    ): ForgeProjectRef? {
        val (owner, name) = GitHubForgeStore.splitOwnerName(path) ?: return null
        if (!apiClient.validateRepo(instance.apiUrl, owner, name, token)) return null
        return ForgeProjectRef(path)
    }

    override fun fetchIssuesSince(
        instance: ForgeInstance,
        path: String,
        token: String?,
        sinceNumber: Int,
    ): List<ForgeItem> {
        val (owner, name) = GitHubForgeStore.splitOwnerName(path) ?: return emptyList()
        return apiClient.fetchIssues(instance.apiUrl, owner, name, token, sinceNumber).map {
            it.toForgeItem()
        }
    }

    override fun fetchChangeRequestsSince(
        instance: ForgeInstance,
        path: String,
        token: String?,
        sinceNumber: Int,
    ): List<ForgeItem> {
        val (owner, name) = GitHubForgeStore.splitOwnerName(path) ?: return emptyList()
        return apiClient.fetchPulls(instance.apiUrl, owner, name, token, sinceNumber).map {
            it.toForgeItem()
        }
    }

    override fun fetchReleases(
        instance: ForgeInstance,
        path: String,
        token: String?,
    ): List<ForgeReleaseInfo> {
        val (owner, name) = GitHubForgeStore.splitOwnerName(path) ?: return emptyList()
        return apiClient.fetchReleases(instance.apiUrl, owner, name, token).map {
            ForgeReleaseInfo(tag = it.tagName, name = it.name, url = it.htmlUrl)
        }
    }

    override fun webhookEnvelope(header: (String) -> String?): WebhookEnvelope? {
        val signature = header("X-Hub-Signature-256")
        val event = header("X-GitHub-Event")
        if (signature.isNullOrBlank() || event.isNullOrBlank()) return null
        return WebhookEnvelope(
            event = event,
            deliveryId = header("X-GitHub-Delivery"),
            signature = signature,
        )
    }

    override fun isSupportedWebhookEvent(envelope: WebhookEnvelope): Boolean =
        envelope.event in supportedEvents

    override fun webhookProjectRef(root: JsonNode): ForgeProjectRef? {
        val fullName = root.path("repository").path("full_name").asString()
        if (fullName.isBlank()) return null
        return if (GitHubForgeStore.splitOwnerName(fullName) == null) null
        else ForgeProjectRef(fullName)
    }

    override fun verifyWebhook(
        envelope: WebhookEnvelope,
        secret: String,
        body: ByteArray,
    ): Boolean {
        val header = envelope.signature ?: return false
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

    override fun parseWebhookEvent(envelope: WebhookEnvelope, root: JsonNode): ForgeEvent? {
        val path = root.path("repository").path("full_name").asString()
        return when (envelope.event) {
            "issues" -> {
                val event = mapper.treeToValue(root, GitHubIssueEvent::class.java)
                if (event.action != "opened") {
                    ignored("issue", path, event.action)
                    return null
                }
                ForgeEvent.IssueOpened(
                    ForgeItem(event.issue.number, event.issue.title, event.issue.htmlUrl)
                )
            }
            "pull_request" -> {
                val event = mapper.treeToValue(root, GitHubPullRequestEvent::class.java)
                if (event.action != "opened") {
                    ignored("pull request", path, event.action)
                    return null
                }
                ForgeEvent.ChangeRequestOpened(
                    ForgeItem(
                        event.pullRequest.number,
                        event.pullRequest.title,
                        event.pullRequest.htmlUrl,
                    )
                )
            }
            "release" -> {
                val event = mapper.treeToValue(root, GitHubReleaseEvent::class.java)
                if (event.action != "published") {
                    ignored("release", path, event.action)
                    return null
                }
                ForgeEvent.ReleasePublished(
                    ForgeReleaseInfo(event.release.tagName, null, event.release.htmlUrl)
                )
            }
            "ping" -> {
                logger.info("Received GitHub webhook ping for {}", path)
                ForgeEvent.Ping(root.path("zen").asString(null))
            }
            else -> null
        }
    }

    private fun ignored(what: String, path: String, action: String) {
        logger.debug(
            "Ignoring GitHub {} webhook for {} with unsupported action {}",
            what,
            path,
            action,
        )
    }

    private fun GitHubApiItem.toForgeItem() =
        ForgeItem(number = number, title = title, url = htmlUrl)

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

    companion object {
        private val supportedEvents = setOf("issues", "pull_request", "release", "ping")
    }
}
