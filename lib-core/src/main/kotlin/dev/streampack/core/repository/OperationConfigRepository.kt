/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.repository

import dev.streampack.core.entity.OperationConfig
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface OperationConfigRepository : JpaRepository<OperationConfig, UUID> {
    fun findByOperationGroup(operationGroup: String): List<OperationConfig>

    fun findByProvenancePatternAndOperationGroup(
        provenancePattern: String,
        operationGroup: String,
    ): OperationConfig?
}
