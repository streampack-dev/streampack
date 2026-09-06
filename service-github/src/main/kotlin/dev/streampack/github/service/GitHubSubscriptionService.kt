/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.service

import dev.streampack.forge.ForgeKind
import dev.streampack.forge.secret.EnvironmentSecretLookup
import dev.streampack.forge.service.AbstractForgeSubscriptionService
import dev.streampack.forge.service.AddInstanceOutcome
import dev.streampack.forge.service.AddProjectOutcome
import dev.streampack.forge.service.RemoveProjectOutcome
import dev.streampack.github.config.GitHubProperties
import dev.streampack.github.entity.GitHubInstance
import dev.streampack.github.entity.GitHubRepo
import dev.streampack.github.entity.GitHubSubscription
import org.springframework.stereotype.Service

/** Orchestrates GitHub instance and repository registration, subscription, and removal */
@Service
class GitHubSubscriptionService(
    store: GitHubForgeStore,
    client: GitHubForgeClient,
    secretLookup: EnvironmentSecretLookup,
    properties: GitHubProperties,
) :
    AbstractForgeSubscriptionService<GitHubInstance, GitHubRepo, GitHubSubscription>(
        ForgeKind.GITHUB,
        store,
        client,
        secretLookup,
        properties.pollInterval,
    ) {

    override fun invalidIdentifierReason(identifier: String): String? =
        if (GitHubForgeStore.splitOwnerName(identifier) == null) "Expected format: owner/repo"
        else null

    /**
     * Register a GitHub instance from a host, base URL, or API URL; see
     * [GitHubInstance.endpointFor] for how the API URL is derived.
     */
    fun addInstance(url: String, token: String?): AddInstanceOutcome<GitHubInstance> {
        val (host, apiUrl) =
            GitHubInstance.endpointFor(url)
                ?: return AddInstanceOutcome.Invalid(
                    url,
                    "Expected a host or URL such as https://ghe.example.com",
                )
        return addInstance(host, apiUrl, token)
    }

    /** Register a GitHub repository for watching on [instance] */
    fun addRepo(
        instance: GitHubInstance,
        ownerRepo: String,
        token: String?,
    ): AddProjectOutcome<GitHubRepo> = addProject(instance, ownerRepo, token)

    /** Deactivate a repository and all its subscriptions */
    fun removeRepo(instance: GitHubInstance, ownerRepo: String): RemoveProjectOutcome<GitHubRepo> =
        removeProject(instance, ownerRepo)

    /** List all registered repositories */
    fun listRepos(): List<GitHubRepo> = listProjects()
}
