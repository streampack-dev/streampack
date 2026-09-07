/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

/**
 * A way a one-time sign-in code can reach someone. On the wire the channel is its lowercase name
 * (`email`, `mattermost`, `sms`), matching what `/features` advertises; parsing is
 * case-insensitive.
 */
enum class CodeChannel {
    EMAIL,
    MATTERMOST,
    SMS;

    /** The lowercase name used in JSON bodies and the features listing */
    @JsonValue fun wireName(): String = name.lowercase()

    companion object {
        /** Parses a wire name regardless of case; unknown names are rejected as bad input. */
        @JvmStatic
        @JsonCreator
        fun fromWireName(value: String): CodeChannel =
            entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown code channel '$value'")
    }
}

/**
 * What a person types to ask for a code: an email address, a chat username on a registered server,
 * or a phone number. [server] is required for chat channels, where one deployment may be connected
 * to several servers.
 */
data class CodeIdentity(val channel: CodeChannel, val address: String, val server: String? = null)

/**
 * What a [dev.streampack.core.service.CodeDelivery] resolved an identity to. [key] is the canonical
 * recipient stored on the code row, so the same person always hits the same active-code budget.
 * Email recipients carry [email]; chat and phone recipients carry the service-binding coordinates
 * and enough profile to create an account on first verify.
 */
data class ResolvedRecipient(
    val key: String,
    val email: String? = null,
    val protocol: Protocol? = null,
    val serviceId: String? = null,
    val externalIdentifier: String? = null,
    val username: String? = null,
    val displayName: String? = null,
)
