/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.secret

import dev.streampack.core.model.SecretRef
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/** Resolves an environment variable name to its value, or null when unset. */
fun interface SecretLookup {
    fun lookup(key: String): String?
}

/** Process environment first, then Spring properties (which lets tests supply values). */
@Component
class EnvironmentSecretLookup(private val environment: Environment) : SecretLookup {
    override fun lookup(key: String): String? = System.getenv(key) ?: environment.getProperty(key)
}

/** Turns a stored [SecretRef] into a usable token for API calls. */
object ForgeTokenResolver {
    /**
     * A literal returns as stored. An `env://KEY` reference returns the variable's value, or null
     * when the variable is unset, so a missing variable never leaks the reference text as a token.
     */
    fun resolve(ref: SecretRef?, lookup: SecretLookup): String? {
        if (ref == null) return null
        val key = ref.envKeyOrNull() ?: return ref.asStoredValue().ifBlank { null }
        return lookup.lookup(key)?.ifBlank { null }
    }
}
