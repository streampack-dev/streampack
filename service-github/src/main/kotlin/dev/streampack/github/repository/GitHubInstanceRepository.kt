/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.repository

import dev.streampack.github.entity.GitHubInstance
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface GitHubInstanceRepository : JpaRepository<GitHubInstance, UUID> {
    fun findByHost(host: String): GitHubInstance?
}
