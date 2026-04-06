/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.model

/** Transient DTO for an issue or pull request from the GitHub API */
data class GitHubApiItem(
    val number: Int,
    val title: String,
    val htmlUrl: String,
    val pullRequest: Boolean,
)
