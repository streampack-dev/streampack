/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.repository

import dev.streampack.github.entity.GitHubPipeline
import dev.streampack.github.entity.GitHubRepo
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface GitHubPipelineRepository : JpaRepository<GitHubPipeline, UUID> {
    fun findByRepoAndPipelineId(repo: GitHubRepo, pipelineId: String): GitHubPipeline?
}
