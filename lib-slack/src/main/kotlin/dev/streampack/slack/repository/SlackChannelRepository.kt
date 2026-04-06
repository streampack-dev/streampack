/* Joseph B. Ottinger (C)2026 */
package dev.streampack.slack.repository

import dev.streampack.slack.entity.SlackChannel
import dev.streampack.slack.entity.SlackWorkspace
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface SlackChannelRepository : JpaRepository<SlackChannel, UUID> {
    fun findByWorkspaceAndNameAndDeletedFalse(
        workspace: SlackWorkspace,
        name: String,
    ): SlackChannel?

    fun findByWorkspaceAndDeletedFalse(workspace: SlackWorkspace): List<SlackChannel>
}
