/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.model.SecretRef

/** Utility helpers for environment-keyed secret references. */
object SecretRefEnvironment {
    fun buildKey(vararg segments: String): String =
        segments.map(::normalizeSegment).filter { it.isNotBlank() }.joinToString("_")

    fun normalizeSegment(value: String): String =
        value.trim().uppercase().replace(Regex("[^A-Z0-9]+"), "_").trim('_')

    fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

    fun resolve(secretRef: SecretRef, lookup: (String) -> String?): String {
        val envKey = secretRef.envKeyOrNull()
        return if (envKey == null) {
            secretRef.asStoredValue()
        } else {
            lookup(envKey) ?: secretRef.asStoredValue()
        }
    }
}
