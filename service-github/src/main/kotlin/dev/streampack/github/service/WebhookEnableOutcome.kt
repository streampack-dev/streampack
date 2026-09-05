/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.service

import dev.streampack.github.entity.GitHubRepo

sealed interface WebhookEnableOutcome {
    /** [repo] carries the instance, which decides the webhook URL the operator must configure. */
    data class Enabled(val repo: GitHubRepo, val secret: String) : WebhookEnableOutcome

    data class RepoInactive(val ownerRepo: String) : WebhookEnableOutcome

    data class InvalidRepo(val reason: String) : WebhookEnableOutcome

    data class ApiFailed(val ownerRepo: String, val reason: String) : WebhookEnableOutcome

    /** The server has no webhook secret key configured, so secrets cannot be stored. */
    data class NotConfigured(val reason: String) : WebhookEnableOutcome
}
