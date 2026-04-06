/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.service

import dev.streampack.core.integration.TickListener
import dev.streampack.github.config.GitHubProperties
import dev.streampack.github.entity.GitHubRelease
import dev.streampack.github.model.DeliveryMode
import dev.streampack.github.repository.GitHubReleaseRepository
import dev.streampack.github.repository.GitHubRepoRepository
import dev.streampack.github.repository.GitHubSubscriptionRepository
import dev.streampack.polling.service.EgressNotifier
import jakarta.annotation.PostConstruct
import java.time.Duration
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Polls active GitHub repos on a tick-driven interval, detects new items, and notifies subscribers
 */
@Service
class GitHubPollingService(
    private val repoRepository: GitHubRepoRepository,
    private val releaseRepository: GitHubReleaseRepository,
    private val subscriptionRepository: GitHubSubscriptionRepository,
    private val apiClient: GitHubApiClient,
    private val egressNotifier: EgressNotifier,
    private val gitHubProperties: GitHubProperties,
) : TickListener {
    private val logger = LoggerFactory.getLogger(GitHubPollingService::class.java)
    private lateinit var lastPollTime: Instant

    /** Delay first poll by 30 seconds so protocol adapters can finish connecting */
    @PostConstruct
    fun initLastPollTime() {
        lastPollTime = Instant.now().minus(gitHubProperties.pollInterval).plusSeconds(30)
    }

    override fun onTick(now: Instant) {
        if (Duration.between(lastPollTime, now) >= gitHubProperties.pollInterval) {
            lastPollTime = now
            pollAllRepos()
        }
    }

    @Transactional
    fun pollAllRepos() {
        val repos = repoRepository.findAllByActiveTrueAndDeliveryMode(DeliveryMode.POLLING)
        logger.debug("Polling {} active GitHub repos", repos.size)
        for (repo in repos) {
            try {
                pollRepo(repo.id.toString())
            } catch (e: Exception) {
                logger.warn("Failed to poll GitHub repo {}: {}", repo.fullName(), e.message)
            }
        }
    }

    /** Poll a single repo by ID, detecting new issues, PRs, and releases */
    @Transactional
    fun pollRepo(repoId: String) {
        val repo = repoRepository.findById(java.util.UUID.fromString(repoId)).orElse(null) ?: return
        val owner = repo.owner
        val name = repo.name
        val token = repo.token
        logger.info(
            "Polling repo {} (since issue {}, PR {})",
            name,
            repo.highestIssueNumber,
            repo.highestPrNumber,
        )

        val newIssues = apiClient.fetchIssues(owner, name, token, repo.highestIssueNumber)
        val newPulls = apiClient.fetchPulls(owner, name, token, repo.highestPrNumber)
        val allReleases = apiClient.fetchReleases(owner, name, token)

        // Detect new releases by comparing tags against known releases
        val knownTags =
            if (allReleases.isNotEmpty()) {
                releaseRepository
                    .findByRepoAndTagIn(repo, allReleases.map { it.tagName })
                    .map { it.tag }
                    .toSet()
            } else {
                emptySet()
            }
        val newReleases = allReleases.filter { it.tagName !in knownTags }

        // Update baseline numbers
        val highestIssue = maxOf(repo.highestIssueNumber, newIssues.maxOfOrNull { it.number } ?: 0)
        val highestPr = maxOf(repo.highestPrNumber, newPulls.maxOfOrNull { it.number } ?: 0)

        repoRepository.save(
            repo.copy(
                highestIssueNumber = highestIssue,
                highestPrNumber = highestPr,
                lastPolledAt = Instant.now(),
            )
        )

        // Save new release tags
        newReleases.forEach { release ->
            releaseRepository.save(
                GitHubRelease(repo = repo, tag = release.tagName, name = release.name)
            )
        }

        if (newIssues.isEmpty() && newPulls.isEmpty() && newReleases.isEmpty()) {
            return
        }

        logger.info(
            "Found {} new issues, {} new PRs, {} new releases for {}",
            newIssues.size,
            newPulls.size,
            newReleases.size,
            repo.fullName(),
        )

        // Notify subscribers
        val subscriptions = subscriptionRepository.findByRepoAndActiveTrue(repo)
        if (subscriptions.isEmpty()) return

        val fullName = repo.fullName()
        for (subscription in subscriptions) {
            for (issue in newIssues) {
                egressNotifier.send(
                    "[$fullName] New issue #${issue.number}: ${issue.title} - ${issue.htmlUrl}",
                    subscription.destinationUri,
                )
            }
            for (pr in newPulls) {
                egressNotifier.send(
                    "[$fullName] New PR #${pr.number}: ${pr.title} - ${pr.htmlUrl}",
                    subscription.destinationUri,
                )
            }
            for (release in newReleases) {
                egressNotifier.send(
                    "[$fullName] New release ${release.tagName} - ${release.htmlUrl}",
                    subscription.destinationUri,
                )
            }
        }
    }
}
