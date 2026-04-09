/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.model

/** Typed request to enable webhook delivery for a GitHub repository. */
data class GitHubWebhookEnableRequest(val ownerRepo: String, val privateMode: Boolean = false)
