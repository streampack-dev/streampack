/* Joseph B. Ottinger (C)2026 */
package dev.streampack.gitlab.service

import dev.streampack.forge.ForgeKind
import dev.streampack.forge.secret.EnvironmentSecretLookup
import dev.streampack.forge.service.AbstractForgeSubscriptionService
import dev.streampack.forge.service.AddInstanceOutcome
import dev.streampack.gitlab.config.ConditionalOnGitLab
import dev.streampack.gitlab.config.GitLabProperties
import dev.streampack.gitlab.entity.GitLabInstance
import dev.streampack.gitlab.entity.GitLabProject
import dev.streampack.gitlab.entity.GitLabSubscription
import org.springframework.stereotype.Service

/** Orchestrates GitLab instance and project registration, subscription, and removal */
@Service
@ConditionalOnGitLab
class GitLabSubscriptionService(
    store: GitLabForgeStore,
    client: GitLabForgeClient,
    secretLookup: EnvironmentSecretLookup,
    properties: GitLabProperties,
) :
    AbstractForgeSubscriptionService<GitLabInstance, GitLabProject, GitLabSubscription>(
        ForgeKind.GITLAB,
        store,
        client,
        secretLookup,
        properties.pollInterval,
    ) {

    override fun invalidIdentifierReason(identifier: String): String? =
        if (GitLabForgeClient.isValidPath(identifier)) null
        else "Expected format: group/project (subgroups allowed: group/sub/project)"

    /** Register a GitLab instance from a host, base URL, or API URL */
    fun addInstance(url: String, token: String?): AddInstanceOutcome<GitLabInstance> {
        val (host, apiUrl) =
            GitLabInstance.endpointFor(url)
                ?: return AddInstanceOutcome.Invalid(
                    url,
                    "Expected a host or URL such as https://gitlab.example.com",
                )
        return addInstance(host, apiUrl, token)
    }
}
