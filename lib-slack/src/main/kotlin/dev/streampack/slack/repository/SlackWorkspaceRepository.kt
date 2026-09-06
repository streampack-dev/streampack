/* Joseph B. Ottinger (C)2026 */
package dev.streampack.slack.repository

import dev.streampack.slack.entity.SlackWorkspace
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface SlackWorkspaceRepository : JpaRepository<SlackWorkspace, UUID> {
    fun findByNameAndDeletedFalse(name: String): SlackWorkspace?

    /** Any row by name, removed or not; names stay unique across soft deletes */
    fun findByName(name: String): SlackWorkspace?

    fun findByDeletedFalse(): List<SlackWorkspace>

    fun findByAutoconnectTrueAndDeletedFalse(): List<SlackWorkspace>
}
