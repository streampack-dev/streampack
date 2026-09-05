/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

/**
 * Well-known placeholder values that must never be accepted as a real secret.
 *
 * These are the strings that ship in sample configuration and documentation. A deployment that
 * boots with one of them has a key anyone with the source can compute, so startup guards reject
 * them regardless of the enforce-external-secrets setting.
 */
object SecretPlaceholders {
    private val values =
        setOf("change-me", "change_me", "changeme", "change-me-in-production", "changeit")

    fun isPlaceholder(candidate: String?): Boolean {
        val normalized = candidate?.trim()?.lowercase() ?: return false
        return normalized in values
    }
}
