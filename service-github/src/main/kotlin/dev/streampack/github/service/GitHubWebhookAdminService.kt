/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.service

import dev.streampack.github.model.AddRepoOutcome
import dev.streampack.github.model.DeliveryMode
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

    fun enableWebhook(ownerRepo: String): WebhookEnableOutcome {
        val parts = ownerRepo.split("/")
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return WebhookEnableOutcome.InvalidRepo("Expected owner/repo")
        }
        val owner = parts[0]
        val name = parts[1]
        val repo =
            repoRepository.findByOwnerAndName(owner, name)
                ?: when (val addOutcome = subscriptionService.addRepo("$owner/$name", null)) {
                    is AddRepoOutcome.Added -> addOutcome.repo
                    is AddRepoOutcome.AlreadyExists -> addOutcome.repo
                    is AddRepoOutcome.InvalidRepo ->
                        return WebhookEnableOutcome.InvalidRepo(addOutcome.reason)
                    is AddRepoOutcome.ApiFailed ->
                        return WebhookEnableOutcome.ApiFailed(ownerRepo, addOutcome.reason)
                }
        if (!repo.active) {
            return WebhookEnableOutcome.RepoInactive(ownerRepo)
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
        return WebhookEnableOutcome.Enabled(updated.fullName(), secret)
    }

    private fun generateSecret(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        val builder = StringBuilder(bytes.size * 2)
        bytes.forEach { b -> builder.append(String.format("%02x", b)) }
        return builder.toString()
    }
}
