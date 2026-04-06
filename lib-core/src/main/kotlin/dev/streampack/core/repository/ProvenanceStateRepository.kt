/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.repository

import dev.streampack.core.entity.ProvenanceState
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface ProvenanceStateRepository : JpaRepository<ProvenanceState, UUID> {
    fun findByProvenanceUriAndKey(provenanceUri: String, key: String): ProvenanceState?

    fun deleteByProvenanceUriAndKey(provenanceUri: String, key: String)
}
