/* Joseph B. Ottinger (C)2026 */
package dev.streampack.gitlab.service

import dev.streampack.core.model.SecretRef
import dev.streampack.forge.model.DeliveryMode
import dev.streampack.forge.model.ForgeProjectRef
import dev.streampack.forge.model.ForgeReleaseInfo
import dev.streampack.forge.model.PipelineOutcome
import dev.streampack.forge.store.ForgeStore
import dev.streampack.gitlab.config.ConditionalOnGitLab
import dev.streampack.gitlab.entity.GitLabInstance
import dev.streampack.gitlab.entity.GitLabPipeline
import dev.streampack.gitlab.entity.GitLabProject
import dev.streampack.gitlab.entity.GitLabRelease
import dev.streampack.gitlab.entity.GitLabSubscription
import dev.streampack.gitlab.repository.GitLabInstanceRepository
import dev.streampack.gitlab.repository.GitLabPipelineRepository
import dev.streampack.gitlab.repository.GitLabProjectRepository
import dev.streampack.gitlab.repository.GitLabReleaseRepository
import dev.streampack.gitlab.repository.GitLabSubscriptionRepository
import java.time.Instant
import java.util.UUID
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service

/** GitLab's persistence port: the `gitlab_*` tables behind the shared forge services */
@Service
@ConditionalOnGitLab
class GitLabForgeStore(
    private val instanceRepository: GitLabInstanceRepository,
    private val projectRepository: GitLabProjectRepository,
    private val releaseRepository: GitLabReleaseRepository,
    private val subscriptionRepository: GitLabSubscriptionRepository,
    private val pipelineRepository: GitLabPipelineRepository,
) : ForgeStore<GitLabInstance, GitLabProject, GitLabSubscription> {

    /** The gitlab.com row the migration seeds; recreated if it is ever missing */
    override fun defaultInstance(): GitLabInstance =
        instanceRepository.findByHost(GitLabInstance.DEFAULT_HOST)
            ?: instanceRepository.save(GitLabInstance())

    override fun findInstanceByHost(host: String): GitLabInstance? =
        instanceRepository.findByHost(host)

    override fun findInstanceById(id: String): GitLabInstance? {
        val uuid =
            try {
                UUID.fromString(id)
            } catch (_: IllegalArgumentException) {
                return null
            }
        return instanceRepository.findById(uuid).orElse(null)
    }

    override fun listInstances(): List<GitLabInstance> {
        defaultInstance()
        return instanceRepository.findAll().sortedWith(compareBy({ !it.isDefault }, { it.host }))
    }

    override fun createInstance(
        host: String,
        apiUrl: String,
        defaultToken: SecretRef?,
    ): GitLabInstance =
        instanceRepository.save(
            GitLabInstance(host = host, apiUrl = apiUrl, defaultToken = defaultToken)
        )

    /** Numeric id first (survives renames), then the path (covers private-mode registrations) */
    override fun findProject(instance: GitLabInstance, ref: ForgeProjectRef): GitLabProject? {
        ref.externalId?.toLongOrNull()?.let { id ->
            projectRepository.findByInstanceAndProjectId(instance, id)?.let {
                return it
            }
        }
        if (!GitLabForgeClient.isValidPath(ref.path)) return null
        return projectRepository.findByInstanceAndFullPath(instance, ref.path)
    }

    override fun findProjectById(id: String): GitLabProject? =
        projectRepository.findById(UUID.fromString(id)).orElse(null)

    override fun listProjects(): List<GitLabProject> = projectRepository.findAll()

    override fun findActiveProjects(deliveryMode: DeliveryMode): List<GitLabProject> =
        projectRepository.findAllByActiveTrueAndDeliveryMode(deliveryMode)

    override fun findDueProjects(now: Instant, limit: Int): List<GitLabProject> =
        projectRepository
            .findByActiveTrueAndDeliveryModeAndNextPollAtLessThanEqualOrderByNextPollAtAsc(
                DeliveryMode.POLLING,
                now,
                PageRequest.of(0, limit),
            )

    override fun schedulePoll(
        project: GitLabProject,
        nextPollAt: Instant,
        pollFailures: Int,
    ): GitLabProject =
        projectRepository.save(project.copy(nextPollAt = nextPollAt, pollFailures = pollFailures))

    override fun createProject(
        instance: GitLabInstance,
        ref: ForgeProjectRef,
        token: SecretRef?,
        highestIssueNumber: Int,
        highestChangeRequestNumber: Int,
        polledAt: Instant,
        nextPollAt: Instant,
    ): GitLabProject {
        require(GitLabForgeClient.isValidPath(ref.path)) { "Expected format: group/project" }
        return projectRepository.save(
            GitLabProject(
                instance = instance,
                fullPath = ref.path,
                projectId = ref.externalId?.toLongOrNull(),
                token = token,
                highestIssueNumber = highestIssueNumber,
                highestMrNumber = highestChangeRequestNumber,
                lastPolledAt = polledAt,
                nextPollAt = nextPollAt,
            )
        )
    }

    override fun updateCursors(
        project: GitLabProject,
        highestIssueNumber: Int,
        highestChangeRequestNumber: Int,
        polledAt: Instant,
    ): GitLabProject =
        projectRepository.save(
            project.copy(
                highestIssueNumber = highestIssueNumber,
                highestMrNumber = highestChangeRequestNumber,
                lastPolledAt = polledAt,
            )
        )

    override fun deactivateProject(project: GitLabProject): GitLabProject =
        projectRepository.save(project.copy(active = false))

    /** Switch [project] to webhook delivery with an already-encrypted secret token */
    fun saveWebhookSecret(project: GitLabProject, encryptedSecret: String): GitLabProject =
        projectRepository.save(
            project.copy(
                deliveryMode = DeliveryMode.WEBHOOK,
                webhookSecret = encryptedSecret,
                webhookConfiguredAt = Instant.now(),
            )
        )

    override fun knownReleaseTags(project: GitLabProject, tags: List<String>): Set<String> =
        releaseRepository.findByProjectAndTagIn(project, tags).map { it.tag }.toSet()

    override fun saveRelease(project: GitLabProject, release: ForgeReleaseInfo) {
        releaseRepository.save(
            GitLabRelease(project = project, tag = release.tag, name = release.name)
        )
    }

    override fun findSubscription(
        project: GitLabProject,
        destinationUri: String,
    ): GitLabSubscription? =
        subscriptionRepository.findByProjectAndDestinationUri(project, destinationUri)

    override fun createSubscription(
        project: GitLabProject,
        destinationUri: String,
    ): GitLabSubscription =
        subscriptionRepository.save(
            GitLabSubscription(project = project, destinationUri = destinationUri)
        )

    override fun setSubscriptionActive(
        subscription: GitLabSubscription,
        active: Boolean,
    ): GitLabSubscription = subscriptionRepository.save(subscription.copy(active = active))

    override fun setSubscriptionEvents(
        subscription: GitLabSubscription,
        events: List<String>,
    ): GitLabSubscription = subscriptionRepository.save(subscription.copy(events = events))

    override fun pipelineStatus(project: GitLabProject, pipelineId: String): PipelineOutcome? =
        pipelineRepository.findByProjectAndPipelineId(project, pipelineId)?.lastStatus

    override fun recordPipeline(
        project: GitLabProject,
        pipelineId: String,
        outcome: PipelineOutcome,
    ) {
        val existing = pipelineRepository.findByProjectAndPipelineId(project, pipelineId)
        pipelineRepository.save(
            existing?.copy(lastStatus = outcome, updatedAt = Instant.now())
                ?: GitLabPipeline(project = project, pipelineId = pipelineId, lastStatus = outcome)
        )
    }

    override fun findActiveSubscriptions(project: GitLabProject): List<GitLabSubscription> =
        subscriptionRepository.findByProjectAndActiveTrue(project)

    override fun findActiveSubscriptionsByDestination(
        destinationUri: String
    ): List<GitLabSubscription> =
        subscriptionRepository.findByDestinationUriAndActiveTrue(destinationUri)
}
