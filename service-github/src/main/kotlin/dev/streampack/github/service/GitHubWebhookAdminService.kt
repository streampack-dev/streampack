/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.service

import dev.streampack.forge.model.DeliveryMode
import dev.streampack.forge.service.AddProjectOutcome
import dev.streampack.github.entity.GitHubInstance
import dev.streampack.github.entity.GitHubRepo
import dev.streampack.github.repository.GitHubRepoRepository
import java.security.SecureRandom
import java.time.Instant
import org.springframework.stereotype.Service

/** Enables webhook delivery for GitHub repositories and generates secrets */
@Service
class GitHubWebhookAdminService(
    private val repoRepository: GitHubRepoRepository,
    private val subscriptionService: GitHubSubscriptionService,
    private val secretCipher: WebhookSecretCipher,
) {

    private val secureRandom = SecureRandom()

    /** The instance `on <host>` names, or github.com when [host] is null; null when unknown */
    fun instanceFor(host: String?): GitHubInstance? = subscriptionService.instanceFor(host)

    fun enableWebhook(
        instance: GitHubInstance,
        ownerRepo: String,
        privateMode: Boolean = false,
    ): WebhookEnableOutcome {
        if (!secretCipher.isConfigured) {
            return WebhookEnableOutcome.NotConfigured(WebhookSecretCipher.NOT_CONFIGURED_MESSAGE)
        }
        val (owner, name) =
            GitHubForgeStore.splitOwnerName(ownerRepo)
                ?: return WebhookEnableOutcome.InvalidRepo("Expected owner/repo")
        val repo =
            repoRepository.findByInstanceAndOwnerAndName(instance, owner, name)
                ?: if (privateMode) {
                    repoRepository.save(GitHubRepo(instance = instance, owner = owner, name = name))
                } else {
                    when (
                        val addOutcome = subscriptionService.addRepo(instance, "$owner/$name", null)
                    ) {
                        is AddProjectOutcome.Added -> addOutcome.project
                        is AddProjectOutcome.AlreadyExists -> addOutcome.project
                        is AddProjectOutcome.InvalidIdentifier ->
                            return WebhookEnableOutcome.InvalidRepo(addOutcome.reason)
                        is AddProjectOutcome.ApiFailed ->
                            return WebhookEnableOutcome.ApiFailed(ownerRepo, addOutcome.reason)
                    }
                }
        if (!repo.active) {
            return WebhookEnableOutcome.RepoInactive(repo.displayName)
        }

        val secret = generateSecret()
        val encrypted = secretCipher.encrypt(secret)
        val updated =
            repoRepository.save(
                repo.copy(
                    deliveryMode = DeliveryMode.WEBHOOK,
                    webhookSecret = encrypted,
                    webhookConfiguredAt = Instant.now(),
                )
            )
        return WebhookEnableOutcome.Enabled(updated, secret)
    }

    private fun generateSecret(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        val builder = StringBuilder(bytes.size * 2)
        bytes.forEach { b -> builder.append(String.format("%02x", b)) }
        return builder.toString()
    }
}
