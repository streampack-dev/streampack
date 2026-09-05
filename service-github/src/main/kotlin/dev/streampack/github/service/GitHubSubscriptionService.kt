/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.service

import dev.streampack.forge.ForgeKind
import dev.streampack.forge.secret.EnvironmentSecretLookup
import dev.streampack.forge.service.AbstractForgeSubscriptionService
import dev.streampack.forge.service.AddProjectOutcome
import dev.streampack.forge.service.RemoveProjectOutcome
import dev.streampack.github.entity.GitHubRepo
import dev.streampack.github.entity.GitHubSubscription
import org.springframework.stereotype.Service

/** Orchestrates GitHub repository registration, subscription, and removal */
@Service
class GitHubSubscriptionService(
    store: GitHubForgeStore,
    client: GitHubForgeClient,
    secretLookup: EnvironmentSecretLookup,
) :
    AbstractForgeSubscriptionService<GitHubRepo, GitHubSubscription>(
        ForgeKind.GITHUB,
        store,
        client,
        secretLookup,
    ) {

    override fun invalidIdentifierReason(identifier: String): String? =
        if (GitHubForgeStore.splitOwnerName(identifier) == null) "Expected format: owner/repo"
        else null

    /** Register a GitHub repository for watching */
    fun addRepo(ownerRepo: String, token: String?): AddProjectOutcome<GitHubRepo> =
        addProject(ownerRepo, token)

    /** Deactivate a repository and all its subscriptions */
    fun removeRepo(ownerRepo: String): RemoveProjectOutcome<GitHubRepo> = removeProject(ownerRepo)

    /** List all registered repositories */
    fun listRepos(): List<GitHubRepo> = listProjects()
}
