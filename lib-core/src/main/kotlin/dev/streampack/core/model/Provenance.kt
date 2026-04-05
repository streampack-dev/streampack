/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.model

import java.net.URI
import java.time.Instant

data class Provenance(
    val protocol: Protocol,
    val serviceId: String? = null,
    val user: UserPrincipal? = null,
    val replyTo: String,
    val alsoNotify: List<String> = emptyList(),
    val correlationId: String? = null,
    val timestamp: Instant = Instant.now(),
    val metadata: Map<String, Any> = emptyMap(),
) {
    /** Returns a copy with loopback flag set in metadata */
    fun withLoopback(): Provenance = copy(metadata = metadata + (LOOPBACK_KEY to true))

    /** Checks whether the loopback flag is set in metadata */
    fun isLoopback(): Boolean = metadata[LOOPBACK_KEY] == true

    /** Returns a copy with loopback flag removed from metadata */
    fun clearLoopback(): Provenance = copy(metadata = metadata - LOOPBACK_KEY)

    /** Encodes this provenance as a URI: protocol://serviceId/address */
    fun encode(): String {
        val scheme = protocol.name.lowercase()
        val authority = serviceId ?: ""
        val path = "/$replyTo"
        return URI(scheme, authority, path, null, null).toASCIIString()
    }

    companion object {
        const val HEADER = "provenance"
        const val BOT_NICK = "botNick"
        const val ADDRESSED = "addressed"
        const val IS_ACTION = "isAction"
        const val LOOPBACK_KEY = "loopback"
        const val BRIDGED = "streampack_bridged"

        /** Decodes a URI-format address string into a Provenance */
        fun decode(uri: String): Provenance {
            val parsed = URI(uri)
            val protocol = Protocol.valueOf(parsed.scheme.uppercase())
            val authority = parsed.authority
            val serviceId = if (authority.isNullOrEmpty()) null else authority
            val replyTo = parsed.path.removePrefix("/")
            return Provenance(protocol = protocol, serviceId = serviceId, replyTo = replyTo)
        }
    }
}
