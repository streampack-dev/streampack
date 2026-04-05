/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.repository

import com.enigmastation.streampack.core.entity.ChannelControlOptions
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface ChannelControlOptionsRepository : JpaRepository<ChannelControlOptions, UUID> {
    fun findByProvenanceUriAndDeletedFalse(provenanceUri: String): ChannelControlOptions?

    fun findByAutojoinTrueAndDeletedFalse(): List<ChannelControlOptions>

    /** All active/logged channel controls, including hidden entries (admin view). */
    @Query(
        "SELECT c FROM ChannelControlOptions c WHERE c.deleted = false AND c.active = true AND c.logged = true"
    )
    fun findBrowsableChannelsForAdmin(): List<ChannelControlOptions>

    /** Active/logged/visible channel controls (non-admin and anonymous view). */
    @Query(
        "SELECT c FROM ChannelControlOptions c WHERE c.deleted = false AND c.active = true AND c.logged = true AND c.visible = true"
    )
    fun findBrowsableChannelsForUser(): List<ChannelControlOptions>
}
