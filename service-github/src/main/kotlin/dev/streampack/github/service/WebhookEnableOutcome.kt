/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.service

sealed interface WebhookEnableOutcome {
    data class Enabled(val ownerRepo: String, val secret: String) : WebhookEnableOutcome

    data class RepoInactive(val ownerRepo: String) : WebhookEnableOutcome

    data class InvalidRepo(val reason: String) : WebhookEnableOutcome

    data class ApiFailed(val ownerRepo: String, val reason: String) : WebhookEnableOutcome
}
