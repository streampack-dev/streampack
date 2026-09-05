/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.service

import dev.streampack.forge.ForgeKind
import dev.streampack.forge.webhook.ForgeWebhookFanOut
import dev.streampack.github.entity.GitHubRepo
import dev.streampack.github.entity.GitHubSubscription
import dev.streampack.polling.service.EgressNotifier
import org.springframework.stereotype.Service

/** Formats webhook events and emits notifications identical to polling output */
@Service
class GitHubWebhookService(store: GitHubForgeStore, notifier: EgressNotifier) :
    ForgeWebhookFanOut<GitHubRepo, GitHubSubscription>(ForgeKind.GITHUB, store, notifier)
