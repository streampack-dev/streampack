/* Joseph B. Ottinger (C)2026 */
package dev.streampack.gitlab.service

import dev.streampack.forge.model.ForgeProjectRef
import dev.streampack.forge.service.AddProjectOutcome
import dev.streampack.gitlab.config.ConditionalOnGitLab
import dev.streampack.gitlab.entity.GitLabInstance
import dev.streampack.gitlab.entity.GitLabProject
import java.security.SecureRandom
import java.time.Instant
import org.springframework.stereotype.Service

sealed interface GitLabWebhookEnableOutcome {
    /** [project] carries the instance, which decides the webhook URL the operator must configure */
    data class Enabled(val project: GitLabProject, val secret: String) : GitLabWebhookEnableOutcome

    data class ProjectInactive(val name: String) : GitLabWebhookEnableOutcome

    data class InvalidProject(val reason: String) : GitLabWebhookEnableOutcome

    data class ApiFailed(val path: String, val reason: String) : GitLabWebhookEnableOutcome

    /** The server has no webhook secret key configured, so secrets cannot be stored */
    data class NotConfigured(val reason: String) : GitLabWebhookEnableOutcome
}

/** Enables webhook delivery for GitLab projects and generates their secret tokens */
@Service
@ConditionalOnGitLab
class GitLabWebhookAdminService(
    private val store: GitLabForgeStore,
    private val subscriptionService: GitLabSubscriptionService,
    private val secretCipher: GitLabWebhookSecretCipher,
) {
    private val secureRandom = SecureRandom()

    /** The instance `on <host>` names, or gitlab.com when [host] is null; null when unknown */
    fun instanceFor(host: String?): GitLabInstance? = subscriptionService.instanceFor(host)

    /**
     * Switch a project to webhook delivery. Private mode skips the API lookup, so the project is
     * recorded without its numeric id and deliveries match it by path.
     */
    fun enableWebhook(
        instance: GitLabInstance,
        path: String,
        privateMode: Boolean = false,
    ): GitLabWebhookEnableOutcome {
        if (!secretCipher.isConfigured) {
            return GitLabWebhookEnableOutcome.NotConfigured(
                GitLabWebhookSecretCipher.NOT_CONFIGURED_MESSAGE
            )
        }
        if (!GitLabForgeClient.isValidPath(path)) {
            return GitLabWebhookEnableOutcome.InvalidProject("Expected group/project")
        }
        val project =
            store.findProject(instance, ForgeProjectRef(path))
                ?: if (privateMode) {
                    store.createProject(instance, ForgeProjectRef(path), null, 0, 0, Instant.now())
                } else {
                    when (val outcome = subscriptionService.addProject(instance, path, null)) {
                        is AddProjectOutcome.Added -> outcome.project
                        is AddProjectOutcome.AlreadyExists -> outcome.project
                        is AddProjectOutcome.InvalidIdentifier ->
                            return GitLabWebhookEnableOutcome.InvalidProject(outcome.reason)
                        is AddProjectOutcome.ApiFailed ->
                            return GitLabWebhookEnableOutcome.ApiFailed(path, outcome.reason)
                    }
                }
        if (!project.active) {
            return GitLabWebhookEnableOutcome.ProjectInactive(project.displayName)
        }

        val secret = generateSecret()
        val updated = store.saveWebhookSecret(project, secretCipher.encrypt(secret))
        return GitLabWebhookEnableOutcome.Enabled(updated, secret)
    }

    private fun generateSecret(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
