/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import com.enigmastation.streampack.core.entity.ProvenanceState
import com.enigmastation.streampack.core.repository.ProvenanceStateRepository
import java.time.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Manages per-provenance runtime state as JSONB documents keyed by provenance URI and feature key
 */
@Service
class ProvenanceStateService(private val repository: ProvenanceStateRepository) {

    /** Returns the state data for a provenance/key pair, or null when no state exists */
    fun getState(provenanceUri: String, key: String): Map<String, Any>? {
        return repository.findByProvenanceUriAndKey(provenanceUri, key)?.data
    }

    /** Creates or updates the state data for a provenance/key pair */
    @Transactional
    fun setState(provenanceUri: String, key: String, data: Map<String, Any>) {
        val existing = repository.findByProvenanceUriAndKey(provenanceUri, key)
        if (existing != null) {
            repository.save(existing.copy(data = data, updatedAt = Instant.now()))
        } else {
            repository.save(ProvenanceState(provenanceUri = provenanceUri, key = key, data = data))
        }
    }

    /** Removes the state row for a provenance/key pair */
    @Transactional
    fun clearState(provenanceUri: String, key: String) {
        repository.deleteByProvenanceUriAndKey(provenanceUri, key)
    }
}
