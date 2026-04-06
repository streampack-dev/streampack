/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.model

/** Transient DTO for a release from the GitHub API */
data class GitHubApiRelease(val tagName: String, val name: String?, val htmlUrl: String)
