/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.model

import dev.streampack.github.entity.GitHubRepo

/** Result of attempting to add a GitHub repository */
sealed interface AddRepoOutcome {
    data class Added(
        val repo: GitHubRepo,
        val issueCount: Int,
        val prCount: Int,
        val releaseCount: Int,
    ) : AddRepoOutcome

    data class AlreadyExists(val repo: GitHubRepo) : AddRepoOutcome

    data class InvalidRepo(val ownerRepo: String, val reason: String) : AddRepoOutcome

    data class ApiFailed(val ownerRepo: String, val reason: String) : AddRepoOutcome
}
