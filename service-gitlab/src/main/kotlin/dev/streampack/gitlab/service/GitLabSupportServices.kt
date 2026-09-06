/* Joseph B. Ottinger (C)2026 */
package dev.streampack.gitlab.service

import dev.streampack.forge.ForgeKind
import dev.streampack.forge.secret.EnvironmentSecretLookup
import dev.streampack.forge.service.AbstractForgePollingService
import dev.streampack.forge.service.ForgePollingSchedule
import dev.streampack.forge.webhook.ForgeWebhookFanOut
import dev.streampack.forge.webhook.SecretCipher
import dev.streampack.forge.webhook.WebhookDeliveryTracker
import dev.streampack.gitlab.config.ConditionalOnGitLab
import dev.streampack.gitlab.config.GitLabProperties
import dev.streampack.gitlab.entity.GitLabInstance
import dev.streampack.gitlab.entity.GitLabProject
import dev.streampack.gitlab.entity.GitLabSubscription
import dev.streampack.polling.service.EgressNotifier
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional

/** Polls due GitLab projects in bounded batches and notifies subscribers */
@Service
@ConditionalOnGitLab
class GitLabPollingService(
    store: GitLabForgeStore,
    client: GitLabForgeClient,
    egressNotifier: EgressNotifier,
    properties: GitLabProperties,
    secretLookup: EnvironmentSecretLookup,
    transactionManager: PlatformTransactionManager,
) :
    AbstractForgePollingService<GitLabInstance, GitLabProject, GitLabSubscription>(
        ForgeKind.GITLAB,
        store,
        client,
        egressNotifier,
        ForgePollingSchedule(
            pollInterval = properties.pollInterval,
            schedulerInterval = properties.schedulerInterval,
            batchSize = properties.batchSize,
            maxBackoff = properties.maxBackoff,
        ),
        secretLookup,
        transactionManager,
    ) {
    override fun projectId(project: GitLabProject): String = project.id.toString()

    @Transactional override fun pollProject(projectId: String) = super.pollProject(projectId)
}

/** Formats webhook events and emits notifications identical to polling output */
@Service
@ConditionalOnGitLab
class GitLabWebhookService(store: GitLabForgeStore, notifier: EgressNotifier) :
    ForgeWebhookFanOut<GitLabInstance, GitLabProject, GitLabSubscription>(
        ForgeKind.GITLAB,
        store,
        notifier,
    )

/** Deduplicates GitLab webhook deliveries by `X-Gitlab-Event-UUID` */
@Service
@ConditionalOnGitLab
class GitLabWebhookDeliveryTracker(properties: GitLabProperties) :
    WebhookDeliveryTracker(properties.deliveryDedupeTtl)

/**
 * Encrypts and decrypts GitLab webhook secret tokens for at-rest storage under
 * `streampack.gitlab.webhook-secret-key`. See [SecretCipher] for the placeholder and blank-key
 * rules.
 */
@Component
@ConditionalOnGitLab
class GitLabWebhookSecretCipher(properties: GitLabProperties) :
    SecretCipher(
        configuredKey = properties.webhookSecretKey,
        propertyDescription = "streampack.gitlab.webhook-secret-key (GITLAB_WEBHOOK_SECRET_KEY)",
        kindLabel = "GitLab",
        notConfiguredMessage = NOT_CONFIGURED_MESSAGE,
    ) {
    companion object {
        const val NOT_CONFIGURED_MESSAGE =
            "GitLab webhook delivery requires GITLAB_WEBHOOK_SECRET_KEY " +
                "(streampack.gitlab.webhook-secret-key) to be set"
    }
}
