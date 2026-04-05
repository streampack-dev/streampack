/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.model

/**
 * Typed representation of a persisted secret value.
 *
 * Values may be either literal text or an environment reference in `env://KEY_NAME` form.
 */
data class SecretRef(private val value: String) {
    companion object {
        private const val ENV_PREFIX = "env://"
        private val envKeyPattern = Regex("^[A-Z0-9_]+$")

        fun literal(value: String): SecretRef = SecretRef(value)

        fun env(key: String): SecretRef = SecretRef("$ENV_PREFIX${normalizeKey(key)}")

        fun parse(stored: String): SecretRef = SecretRef(stored)

        private fun normalizeKey(key: String): String = key.trim().uppercase()
    }

    fun asStoredValue(): String = value

    fun isEnvRef(): Boolean = value.startsWith(ENV_PREFIX)

    fun envKeyOrNull(): String? {
        if (!isEnvRef()) return null
        val key = value.removePrefix(ENV_PREFIX).trim()
        return if (envKeyPattern.matches(key)) key else null
    }

    fun resolve(environment: Map<String, String> = System.getenv()): String? {
        val key = envKeyOrNull() ?: return value
        return environment[key]
    }

    fun resolveOrStored(environment: Map<String, String> = System.getenv()): String =
        resolve(environment) ?: value
}
