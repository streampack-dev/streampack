/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.model

/**
 * An environment variable the server needs set before its next start: a credential given at the
 * console is stored in the database until its variable exists, and the startup guards refuse to
 * start with a reference whose variable is missing. Names and states only, never values (#209).
 */
data class SecretRequirement(val variable: String, val description: String, val state: State) {
    enum class State {
        /**
         * Given at the console and stored as it is; the next start moves it to [variable], once
         * set.
         */
        STORED_IN_DATABASE,

        /** Stored as a reference to [variable], which isn't set. */
        NOT_SET,
    }

    companion object {
        /**
         * What [current] needs from the environment, as a startup guard would judge it, or null
         * when it needs nothing that can be given: nothing stored, a blank value, a variable that's
         * already set, or a reference that isn't a variable name. [required] is false where an
         * unset reference doesn't stop a start (inactive projects, say).
         */
        fun of(
            current: SecretRef?,
            envKey: String,
            description: String,
            lookup: (String) -> String?,
            required: Boolean = true,
        ): SecretRequirement? {
            if (current == null) return null
            if (!current.isEnvRef()) {
                if (current.asStoredValue().isBlank() || !lookup(envKey).isNullOrBlank())
                    return null
                return SecretRequirement(envKey, description, State.STORED_IN_DATABASE)
            }
            val key = current.envKeyOrNull() ?: return null
            if (!required || !lookup(key).isNullOrBlank()) return null
            return SecretRequirement(key, description, State.NOT_SET)
        }
    }
}

/** Asks which environment variables the next start needs ([SecretRequirement]). */
data object SecretRequirementsRequest
