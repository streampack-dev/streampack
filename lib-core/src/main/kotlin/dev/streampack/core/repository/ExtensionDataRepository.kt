/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.repository

import dev.streampack.core.entity.ExtensionData
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface ExtensionDataRepository : JpaRepository<ExtensionData, UUID> {
    fun findByExtensionAndCollectionAndItemId(
        extension: String,
        collection: String,
        itemId: String,
    ): ExtensionData?

    fun findByExtensionAndCollection(extension: String, collection: String): List<ExtensionData>

    fun deleteByExtensionAndCollectionAndItemId(
        extension: String,
        collection: String,
        itemId: String,
    ): Long
}
