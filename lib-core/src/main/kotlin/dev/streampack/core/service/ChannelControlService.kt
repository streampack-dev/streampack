/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import com.enigmastation.streampack.core.entity.ChannelControlOptions
import com.enigmastation.streampack.core.repository.ChannelControlOptionsRepository
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** Manages protocol-agnostic channel governance flags keyed by provenance URI */
@Service
class ChannelControlService(private val repository: ChannelControlOptionsRepository) {
    private val logger = LoggerFactory.getLogger(ChannelControlService::class.java)

    /** Returns options for the given provenance URI, or null if none exist */
    fun getOptions(provenanceUri: String): ChannelControlOptions? =
        repository.findByProvenanceUriAndDeletedFalse(provenanceUri)

    /** Returns existing options or creates a new entry with defaults */
    fun getOrCreateOptions(provenanceUri: String): ChannelControlOptions {
        val existing = repository.findByProvenanceUriAndDeletedFalse(provenanceUri)
        if (existing != null) return existing
        logger.debug("Creating default channel control options for {}", provenanceUri)
        return repository.save(ChannelControlOptions(provenanceUri = provenanceUri))
    }

    /** Updates a single flag on the options for the given provenance URI */
    fun setFlag(provenanceUri: String, flag: String, value: Boolean): ChannelControlOptions {
        val options = getOrCreateOptions(provenanceUri)
        val updated =
            when (flag) {
                "autojoin" -> options.copy(autojoin = value, updatedAt = Instant.now())
                "automute" -> options.copy(automute = value, updatedAt = Instant.now())
                "visible" -> options.copy(visible = value, updatedAt = Instant.now())
                "logged" -> options.copy(logged = value, updatedAt = Instant.now())
                "active" -> options.copy(active = value, updatedAt = Instant.now())
                else -> throw IllegalArgumentException("Unknown channel control flag: $flag")
            }
        return repository.save(updated)
    }

    /** Returns all channels marked for autojoin */
    fun findAutojoins(): List<ChannelControlOptions> =
        repository.findByAutojoinTrueAndDeletedFalse()
}
