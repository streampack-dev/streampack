/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.repository

import dev.streampack.github.entity.GitHubRepo
import dev.streampack.github.model.DeliveryMode
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface GitHubRepoRepository : JpaRepository<GitHubRepo, UUID> {
    fun findByOwnerAndName(owner: String, name: String): GitHubRepo?

    fun findAllByActiveTrue(): List<GitHubRepo>

    fun findAllByActiveTrueAndDeliveryMode(deliveryMode: DeliveryMode): List<GitHubRepo>
}
