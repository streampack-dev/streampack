/* Joseph B. Ottinger (C)2026 */
package dev.streampack.gitlab.model

/**
 * Typed request to register a GitLab project for watching. [host] selects the instance (`on
 * <host>`); null means gitlab.com.
 */
data class AddProjectRequest(val path: String, val token: String? = null, val host: String? = null)

/** Typed request to enable webhook delivery for a GitLab project. */
data class GitLabWebhookEnableRequest(
    val path: String,
    val privateMode: Boolean = false,
    val host: String? = null,
)

/** Typed requests behind the `gitlab instance ...` commands */
sealed interface GitLabInstanceRequest {
    /** `gitlab instance add <url> [token]` */
    data class Add(val url: String, val token: String? = null) : GitLabInstanceRequest

    /** `gitlab instance list` */
    data object List : GitLabInstanceRequest
}
