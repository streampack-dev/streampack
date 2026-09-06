/* Joseph B. Ottinger (C)2026 */
package dev.streampack.gitlab.repository

import dev.streampack.forge.model.DeliveryMode
import dev.streampack.gitlab.entity.GitLabInstance
import dev.streampack.gitlab.entity.GitLabPipeline
import dev.streampack.gitlab.entity.GitLabProject
import dev.streampack.gitlab.entity.GitLabRelease
import dev.streampack.gitlab.entity.GitLabSubscription
import java.time.Instant
import java.util.UUID
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface GitLabInstanceRepository : JpaRepository<GitLabInstance, UUID> {
    fun findByHost(host: String): GitLabInstance?
}

interface GitLabProjectRepository : JpaRepository<GitLabProject, UUID> {
    fun findByInstanceAndFullPath(instance: GitLabInstance, fullPath: String): GitLabProject?

    fun findByInstanceAndProjectId(instance: GitLabInstance, projectId: Long): GitLabProject?

    fun findAllByActiveTrueAndDeliveryMode(deliveryMode: DeliveryMode): List<GitLabProject>

    /** Active projects in [deliveryMode] due at or before [now], oldest due first */
    fun findByActiveTrueAndDeliveryModeAndNextPollAtLessThanEqualOrderByNextPollAtAsc(
        deliveryMode: DeliveryMode,
        now: Instant,
        pageable: Pageable,
    ): List<GitLabProject>
}

interface GitLabReleaseRepository : JpaRepository<GitLabRelease, UUID> {
    fun findByProjectAndTagIn(project: GitLabProject, tags: List<String>): List<GitLabRelease>
}

interface GitLabSubscriptionRepository : JpaRepository<GitLabSubscription, UUID> {
    fun findByProjectAndDestinationUri(
        project: GitLabProject,
        destinationUri: String,
    ): GitLabSubscription?

    fun findByProjectAndActiveTrue(project: GitLabProject): List<GitLabSubscription>

    fun findByDestinationUriAndActiveTrue(destinationUri: String): List<GitLabSubscription>
}

interface GitLabPipelineRepository : JpaRepository<GitLabPipeline, UUID> {
    fun findByProjectAndPipelineId(project: GitLabProject, pipelineId: String): GitLabPipeline?
}
