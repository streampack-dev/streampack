/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.model

import dev.streampack.github.entity.GitHubRepo

/** Result of attempting to deactivate a GitHub repository */
sealed interface RemoveRepoOutcome {
    data class Removed(val repo: GitHubRepo, val subscriptionsDeactivated: Int) : RemoveRepoOutcome

    data class RepoNotFound(val ownerRepo: String) : RemoveRepoOutcome

    data class AlreadyInactive(val repo: GitHubRepo) : RemoveRepoOutcome
}
