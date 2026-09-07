/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.entity.ChannelControlOptions
import dev.streampack.core.model.Provenance
import dev.streampack.core.repository.ChannelControlOptionsRepository
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

    /**
     * Returns existing options or creates a new entry. A [private] channel (private channel, direct
     * or group message) starts hidden from log browsing and not captured; an operator can opt it in
     * with `visible` and `logged` afterwards. Public channels keep the visible, logged defaults.
     */
    fun getOrCreateOptions(provenanceUri: String, private: Boolean = false): ChannelControlOptions {
        val existing = repository.findByProvenanceUriAndDeletedFalse(provenanceUri)
        if (existing != null) return existing
        logger.debug(
            "Creating {} channel control options for {}",
            if (private) "private" else "default",
            provenanceUri,
        )
        return repository.save(
            ChannelControlOptions(
                provenanceUri = provenanceUri,
                visible = !private,
                logged = !private,
            )
        )
    }

    /**
     * Whether messages for [provenance] may be written to the message log. Governed by the `logged`
     * flag on the channel's controls; a channel with no controls is logged.
     */
    fun isLogged(provenance: Provenance): Boolean {
        val options =
            repository.findByProvenanceUriAndDeletedFalse(provenance.encode())
                ?: repository.findByProvenanceUriAndDeletedFalse(provenance.identityEncode())
                ?: return true
        return options.logged
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
