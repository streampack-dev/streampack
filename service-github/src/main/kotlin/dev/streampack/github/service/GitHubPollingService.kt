/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.service

import dev.streampack.forge.ForgeKind
import dev.streampack.forge.service.AbstractForgePollingService
import dev.streampack.github.config.GitHubProperties
import dev.streampack.github.entity.GitHubRepo
import dev.streampack.github.entity.GitHubSubscription
import dev.streampack.polling.service.EgressNotifier
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Polls active GitHub repos on a tick-driven interval, detects new items, and notifies subscribers
 */
@Service
class GitHubPollingService(
    store: GitHubForgeStore,
    client: GitHubForgeClient,
    egressNotifier: EgressNotifier,
    gitHubProperties: GitHubProperties,
) :
    AbstractForgePollingService<GitHubRepo, GitHubSubscription>(
        ForgeKind.GITHUB,
        store,
        client,
        egressNotifier,
        gitHubProperties.pollInterval,
    ) {

    override fun projectId(project: GitHubRepo): String = project.id.toString()

    @Transactional fun pollAllRepos() = pollAll()

    /** Poll a single repo by ID, detecting new issues, PRs, and releases */
    @Transactional fun pollRepo(repoId: String) = pollProject(repoId)
}
