/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.service

import dev.streampack.github.entity.GitHubRelease
import dev.streampack.github.entity.GitHubRepo
import dev.streampack.github.entity.GitHubSubscription
import dev.streampack.github.model.AddRepoOutcome
import dev.streampack.github.model.GitHubSubscriptionOutcome
import dev.streampack.github.model.RemoveRepoOutcome
import dev.streampack.github.repository.GitHubReleaseRepository
import dev.streampack.github.repository.GitHubRepoRepository
import dev.streampack.github.repository.GitHubSubscriptionRepository
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Orchestrates GitHub repository registration, subscription, and removal */
@Service
class GitHubSubscriptionService(
    private val repoRepository: GitHubRepoRepository,
    private val releaseRepository: GitHubReleaseRepository,
    private val subscriptionRepository: GitHubSubscriptionRepository,
    private val apiClient: GitHubApiClient,
) {
    private val logger = LoggerFactory.getLogger(GitHubSubscriptionService::class.java)

    /** Register a GitHub repository for watching */
    @Transactional
    fun addRepo(ownerRepo: String, token: String?): AddRepoOutcome {
        val parts = ownerRepo.split("/")
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return AddRepoOutcome.InvalidRepo(ownerRepo, "Expected format: owner/repo")
        }
        val owner = parts[0]
        val name = parts[1]

        val existing = repoRepository.findByOwnerAndName(owner, name)
        if (existing != null) {
            return AddRepoOutcome.AlreadyExists(existing)
        }

        return try {
            if (!apiClient.validateRepo(owner, name, token)) {
                return AddRepoOutcome.ApiFailed(ownerRepo, "Repository not found or not accessible")
            }

            // Seed baseline: fetch current issues, PRs, releases
            val issues = apiClient.fetchIssues(owner, name, token, 0)
            val pulls = apiClient.fetchPulls(owner, name, token, 0)
            val releases = apiClient.fetchReleases(owner, name, token)

            val highestIssue = issues.maxOfOrNull { it.number } ?: 0
            val highestPr = pulls.maxOfOrNull { it.number } ?: 0

            val repo =
                repoRepository.save(
                    GitHubRepo(
                        owner = owner,
                        name = name,
                        token = token,
                        highestIssueNumber = highestIssue,
                        highestPrNumber = highestPr,
                        lastPolledAt = Instant.now(),
                    )
                )

            // Seed known release tags
            releases.forEach { release ->
                releaseRepository.save(
                    GitHubRelease(repo = repo, tag = release.tagName, name = release.name)
                )
            }

            logger.info(
                "Added GitHub repo {} ({} issues, {} PRs, {} releases)",
                ownerRepo,
                issues.size,
                pulls.size,
                releases.size,
            )
            AddRepoOutcome.Added(repo, issues.size, pulls.size, releases.size)
        } catch (e: Exception) {
            logger.warn("Failed to add GitHub repo {}: {}", ownerRepo, e.message)
            AddRepoOutcome.ApiFailed(ownerRepo, e.message ?: "Unknown error")
        }
    }

    /** Subscribe a destination to a repository's notifications */
    @Transactional
    fun subscribe(ownerRepo: String, destinationUri: String): GitHubSubscriptionOutcome {
        val repo =
            resolveRepo(ownerRepo) ?: return GitHubSubscriptionOutcome.RepoNotFound(ownerRepo)
        val existing = subscriptionRepository.findByRepoAndDestinationUri(repo, destinationUri)
        if (existing != null && existing.active) {
            return GitHubSubscriptionOutcome.AlreadySubscribed(repo)
        }
        if (existing != null) {
            subscriptionRepository.save(existing.copy(active = true))
        } else {
            subscriptionRepository.save(
                GitHubSubscription(repo = repo, destinationUri = destinationUri)
            )
        }
        logger.info("Subscribed {} to {}", destinationUri, ownerRepo)
        return GitHubSubscriptionOutcome.Subscribed(repo)
    }

    /** Unsubscribe a destination from a repository */
    @Transactional
    fun unsubscribe(ownerRepo: String, destinationUri: String): GitHubSubscriptionOutcome {
        val repo =
            resolveRepo(ownerRepo) ?: return GitHubSubscriptionOutcome.RepoNotFound(ownerRepo)
        val existing = subscriptionRepository.findByRepoAndDestinationUri(repo, destinationUri)
        if (existing == null || !existing.active) {
            return GitHubSubscriptionOutcome.NotSubscribed(repo)
        }
        subscriptionRepository.save(existing.copy(active = false))
        logger.info("Unsubscribed {} from {}", destinationUri, ownerRepo)
        return GitHubSubscriptionOutcome.Unsubscribed(repo)
    }

    /** Deactivate a repository and all its subscriptions */
    @Transactional
    fun removeRepo(ownerRepo: String): RemoveRepoOutcome {
        val repo = resolveRepo(ownerRepo) ?: return RemoveRepoOutcome.RepoNotFound(ownerRepo)
        if (!repo.active) {
            return RemoveRepoOutcome.AlreadyInactive(repo)
        }
        val activeSubscriptions = subscriptionRepository.findByRepoAndActiveTrue(repo)
        activeSubscriptions.forEach { subscriptionRepository.save(it.copy(active = false)) }
        repoRepository.save(repo.copy(active = false))
        logger.info(
            "Removed GitHub repo {} and deactivated {} subscriptions",
            ownerRepo,
            activeSubscriptions.size,
        )
        return RemoveRepoOutcome.Removed(repo, activeSubscriptions.size)
    }

    /** List all registered repositories */
    fun listRepos(): List<GitHubRepo> = repoRepository.findAll()

    /** List active subscriptions for a destination */
    fun listSubscriptions(destinationUri: String): List<GitHubSubscription> =
        subscriptionRepository.findByDestinationUriAndActiveTrue(destinationUri)

    private fun resolveRepo(ownerRepo: String): GitHubRepo? {
        val parts = ownerRepo.split("/")
        if (parts.size != 2) return null
        return repoRepository.findByOwnerAndName(parts[0], parts[1])
    }
}
