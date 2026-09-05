/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.model

/** Typed requests behind the `github instance ...` commands */
sealed interface GitHubInstanceRequest {
    /** `github instance add <url> [token]` */
    data class Add(val url: String, val token: String? = null) : GitHubInstanceRequest

    /** `github instance list` */
    data object List : GitHubInstanceRequest
}
