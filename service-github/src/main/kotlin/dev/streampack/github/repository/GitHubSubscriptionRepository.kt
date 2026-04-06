/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.repository

import dev.streampack.github.entity.GitHubRepo
import dev.streampack.github.entity.GitHubSubscription
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface GitHubSubscriptionRepository : JpaRepository<GitHubSubscription, UUID> {
    fun findByRepoAndDestinationUri(repo: GitHubRepo, destinationUri: String): GitHubSubscription?

    fun findByRepoAndActiveTrue(repo: GitHubRepo): List<GitHubSubscription>

    fun findByDestinationUriAndActiveTrue(destinationUri: String): List<GitHubSubscription>
}
