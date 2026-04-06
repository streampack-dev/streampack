/* Joseph B. Ottinger (C)2026 */
package dev.streampack.bridge.repository

import dev.streampack.bridge.entity.BridgePair
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface BridgePairRepository : JpaRepository<BridgePair, UUID> {
    fun findByFirstUriAndDeletedFalse(firstUri: String): BridgePair?

    fun findBySecondUriAndDeletedFalse(secondUri: String): BridgePair?

    fun findByFirstUriAndSecondUriAndDeletedFalse(firstUri: String, secondUri: String): BridgePair?

    fun findByDeletedFalse(): List<BridgePair>
}
