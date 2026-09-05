/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mattermost.repository

import dev.streampack.mattermost.entity.MattermostChannel
import dev.streampack.mattermost.entity.MattermostServer
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface MattermostChannelRepository : JpaRepository<MattermostChannel, UUID> {
    fun findByServerAndNameAndDeletedFalse(
        server: MattermostServer,
        name: String,
    ): MattermostChannel?

    fun findByServerAndChannelIdAndDeletedFalse(
        server: MattermostServer,
        channelId: String,
    ): MattermostChannel?

    fun findByServerAndDeletedFalse(server: MattermostServer): List<MattermostChannel>
}
