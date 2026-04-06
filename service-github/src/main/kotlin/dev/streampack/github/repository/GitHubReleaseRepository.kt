/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.repository

import dev.streampack.github.entity.GitHubRelease
import dev.streampack.github.entity.GitHubRepo
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface GitHubReleaseRepository : JpaRepository<GitHubRelease, UUID> {
    fun findByRepoAndTagIn(repo: GitHubRepo, tags: List<String>): List<GitHubRelease>
}
