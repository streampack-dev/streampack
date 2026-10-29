/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.entity.ProvenanceState
import dev.streampack.core.repository.ProvenanceStateRepository
import java.time.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** [ProvenanceStateService] as JSONB rows keyed by provenance URI and feature key. */
@Service
class JpaProvenanceStateService(private val repository: ProvenanceStateRepository) :
    ProvenanceStateService {

    override fun getState(provenanceUri: String, key: String): Map<String, Any>? {
        return repository.findByProvenanceUriAndKey(provenanceUri, key)?.data
    }

    @Transactional
    override fun setState(provenanceUri: String, key: String, data: Map<String, Any>) {
        val existing = repository.findByProvenanceUriAndKey(provenanceUri, key)
        if (existing != null) {
            repository.save(existing.copy(data = data, updatedAt = Instant.now()))
        } else {
            repository.save(ProvenanceState(provenanceUri = provenanceUri, key = key, data = data))
        }
    }

    @Transactional
    override fun clearState(provenanceUri: String, key: String) {
        repository.deleteByProvenanceUriAndKey(provenanceUri, key)
    }
}
