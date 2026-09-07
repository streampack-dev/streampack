/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mattermost.repository

import dev.streampack.mattermost.entity.MattermostChannel
import dev.streampack.mattermost.entity.MattermostServer
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface MattermostChannelRepository : JpaRepository<MattermostChannel, UUID> {
    /** Names are unique only within a team, so a name may match several channels */
    fun findByServerAndNameAndDeletedFalse(
        server: MattermostServer,
        name: String,
    ): List<MattermostChannel>

    fun findByServerAndChannelIdAndDeletedFalse(
        server: MattermostServer,
        channelId: String,
    ): MattermostChannel?

    fun findByServerAndDeletedFalse(server: MattermostServer): List<MattermostChannel>
}
