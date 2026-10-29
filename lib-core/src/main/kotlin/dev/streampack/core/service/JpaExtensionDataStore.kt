/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.entity.ExtensionData
import dev.streampack.core.repository.ExtensionDataRepository
import java.time.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** [ExtensionDataStore] as JSONB rows in `extension_data`. */
@Service
class JpaExtensionDataStore(private val repository: ExtensionDataRepository) : ExtensionDataStore {
    override fun get(extension: String, collection: String, id: String): Map<String, Any>? =
        repository.findByExtensionAndCollectionAndItemId(extension, collection, id)?.data

    @Transactional
    override fun put(extension: String, collection: String, id: String, data: Map<String, Any>) {
        val existing = repository.findByExtensionAndCollectionAndItemId(extension, collection, id)
        repository.save(
            existing?.copy(data = data, updatedAt = Instant.now())
                ?: ExtensionData(
                    extension = extension,
                    collection = collection,
                    itemId = id,
                    data = data,
                ),
        )
    }

    @Transactional
    override fun delete(extension: String, collection: String, id: String): Boolean =
        repository.deleteByExtensionAndCollectionAndItemId(extension, collection, id) > 0

    override fun list(extension: String, collection: String): Map<String, Map<String, Any>> =
        repository.findByExtensionAndCollection(extension, collection).associate {
            it.itemId to it.data
        }
}
