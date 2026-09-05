/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.service

import dev.streampack.core.model.SecretRef
import dev.streampack.forge.model.DeliveryMode
import dev.streampack.forge.model.ForgeReleaseInfo
import dev.streampack.forge.store.ForgeStore
import dev.streampack.github.entity.GitHubRelease
import dev.streampack.github.entity.GitHubRepo
import dev.streampack.github.entity.GitHubSubscription
import dev.streampack.github.repository.GitHubReleaseRepository
import dev.streampack.github.repository.GitHubRepoRepository
import dev.streampack.github.repository.GitHubSubscriptionRepository
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service

/** GitHub's persistence port: the `github_*` tables behind the shared forge services */
@Service
class GitHubForgeStore(
    private val repoRepository: GitHubRepoRepository,
    private val releaseRepository: GitHubReleaseRepository,
    private val subscriptionRepository: GitHubSubscriptionRepository,
) : ForgeStore<GitHubRepo, GitHubSubscription> {

    override fun findProject(path: String): GitHubRepo? {
        val (owner, name) = splitOwnerName(path) ?: return null
        return repoRepository.findByOwnerAndName(owner, name)
    }

    override fun findProjectById(id: String): GitHubRepo? =
        repoRepository.findById(UUID.fromString(id)).orElse(null)

    override fun listProjects(): List<GitHubRepo> = repoRepository.findAll()

    override fun findActiveProjects(deliveryMode: DeliveryMode): List<GitHubRepo> =
        repoRepository.findAllByActiveTrueAndDeliveryMode(deliveryMode)

    override fun createProject(
        path: String,
        token: SecretRef?,
        highestIssueNumber: Int,
        highestChangeRequestNumber: Int,
        polledAt: Instant,
    ): GitHubRepo {
        val (owner, name) =
            splitOwnerName(path) ?: throw IllegalArgumentException("Expected format: owner/repo")
        return repoRepository.save(
            GitHubRepo(
                owner = owner,
                name = name,
                token = token,
                highestIssueNumber = highestIssueNumber,
                highestPrNumber = highestChangeRequestNumber,
                lastPolledAt = polledAt,
            )
        )
    }

    override fun updateCursors(
        project: GitHubRepo,
        highestIssueNumber: Int,
        highestChangeRequestNumber: Int,
        polledAt: Instant,
    ): GitHubRepo =
        repoRepository.save(
            project.copy(
                highestIssueNumber = highestIssueNumber,
                highestPrNumber = highestChangeRequestNumber,
                lastPolledAt = polledAt,
            )
        )

    override fun deactivateProject(project: GitHubRepo): GitHubRepo =
        repoRepository.save(project.copy(active = false))

    override fun knownReleaseTags(project: GitHubRepo, tags: List<String>): Set<String> =
        releaseRepository.findByRepoAndTagIn(project, tags).map { it.tag }.toSet()

    override fun saveRelease(project: GitHubRepo, release: ForgeReleaseInfo) {
        releaseRepository.save(
            GitHubRelease(repo = project, tag = release.tag, name = release.name)
        )
    }

    override fun findSubscription(
        project: GitHubRepo,
        destinationUri: String,
    ): GitHubSubscription? =
        subscriptionRepository.findByRepoAndDestinationUri(project, destinationUri)

    override fun createSubscription(
        project: GitHubRepo,
        destinationUri: String,
    ): GitHubSubscription =
        subscriptionRepository.save(
            GitHubSubscription(repo = project, destinationUri = destinationUri)
        )

    override fun setSubscriptionActive(
        subscription: GitHubSubscription,
        active: Boolean,
    ): GitHubSubscription = subscriptionRepository.save(subscription.copy(active = active))

    override fun findActiveSubscriptions(project: GitHubRepo): List<GitHubSubscription> =
        subscriptionRepository.findByRepoAndActiveTrue(project)

    override fun findActiveSubscriptionsByDestination(
        destinationUri: String
    ): List<GitHubSubscription> =
        subscriptionRepository.findByDestinationUriAndActiveTrue(destinationUri)

    companion object {
        /** Splits `owner/repo`, or returns null when the form is wrong */
        fun splitOwnerName(path: String): Pair<String, String>? {
            val parts = path.split("/")
            if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return null
            return parts[0] to parts[1]
        }
    }
}
