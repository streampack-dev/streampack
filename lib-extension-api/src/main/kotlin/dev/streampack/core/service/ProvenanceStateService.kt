/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

/**
 * Per-provenance runtime state: one document for each provenance URI and feature key, such as a
 * game in progress in a channel. The server keeps it (#294); an extension only reads and writes.
 */
interface ProvenanceStateService {
    /** Returns the state data for a provenance/key pair, or null when no state exists. */
    fun getState(provenanceUri: String, key: String): Map<String, Any>?

    /** Creates or updates the state data for a provenance/key pair. */
    fun setState(provenanceUri: String, key: String, data: Map<String, Any>)

    /** Removes the state for a provenance/key pair. */
    fun clearState(provenanceUri: String, key: String)
}
