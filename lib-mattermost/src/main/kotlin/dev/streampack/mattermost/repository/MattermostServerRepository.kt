/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mattermost.repository

import dev.streampack.mattermost.entity.MattermostServer
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface MattermostServerRepository : JpaRepository<MattermostServer, UUID> {
    fun findByNameAndDeletedFalse(name: String): MattermostServer?

    fun findByDeletedFalse(): List<MattermostServer>

    fun findByAutoconnectTrueAndDeletedFalse(): List<MattermostServer>
}
