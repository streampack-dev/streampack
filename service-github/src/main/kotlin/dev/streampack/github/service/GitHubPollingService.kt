/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.service

import dev.streampack.forge.ForgeKind
import dev.streampack.forge.secret.EnvironmentSecretLookup
import dev.streampack.forge.service.AbstractForgePollingService
import dev.streampack.forge.service.ForgePollingSchedule
import dev.streampack.github.config.GitHubProperties
import dev.streampack.github.entity.GitHubInstance
import dev.streampack.github.entity.GitHubRepo
import dev.streampack.github.entity.GitHubSubscription
import dev.streampack.polling.service.EgressNotifier
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional

/** Polls due GitHub repositories in bounded batches, detects new items, and notifies subscribers */
@Service
class GitHubPollingService(
    store: GitHubForgeStore,
    client: GitHubForgeClient,
    egressNotifier: EgressNotifier,
    gitHubProperties: GitHubProperties,
    secretLookup: EnvironmentSecretLookup,
    transactionManager: PlatformTransactionManager,
) :
    AbstractForgePollingService<GitHubInstance, GitHubRepo, GitHubSubscription>(
        ForgeKind.GITHUB,
        store,
        client,
        egressNotifier,
        ForgePollingSchedule(
            pollInterval = gitHubProperties.pollInterval,
            schedulerInterval = gitHubProperties.schedulerInterval,
            batchSize = gitHubProperties.batchSize,
            maxBackoff = gitHubProperties.maxBackoff,
        ),
        secretLookup,
        transactionManager,
    ) {

    override fun projectId(project: GitHubRepo): String = project.id.toString()

    /** Poll a single repo by ID, detecting new issues, PRs, releases, and settled pipelines */
    @Transactional fun pollRepo(repoId: String) = pollProject(repoId)
}
