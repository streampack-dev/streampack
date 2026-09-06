/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.model

/**
 * Typed request to enable webhook delivery for a GitHub repository. [host] selects the instance
 * (`on <host>`); null means github.com.
 */
data class GitHubWebhookEnableRequest(
    val ownerRepo: String,
    val privateMode: Boolean = false,
    val host: String? = null,
)
