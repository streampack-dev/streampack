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

    /**
     * Encodes the stable identity portion of this provenance.
     *
     * Discord guild targets may include human-readable display labels after the channel ID. Those
     * labels are useful for logs, but they must not affect routing/config identity.
     */
    fun identityEncode(): String {
        if (protocol == Protocol.DISCORD && serviceId != null) {
            val channelId = replyTo.substringBefore("/")
            if (DISCORD_SNOWFLAKE.matches(channelId)) {
                return copy(replyTo = channelId).encode()
            }
        }
        return encode()
    }

    companion object {
        const val HEADER = "provenance"
        const val BOT_NICK = "botNick"
        const val ADDRESSED = "addressed"
        const val IS_ACTION = "isAction"
        const val LOOPBACK_KEY = "loopback"
        const val BRIDGED = "streampack_bridged"

        /**
         * Message header: the protocol's own name for who sent the message (a nick on IRC, a user
         * id on Slack, Discord and Mattermost), which a
         * [dev.streampack.core.service.SenderNotifier] uses to reach them privately (#148).
         * Adapters set it on what people say, not on events such as joins, so nothing is ever sent
         * about those.
         */
        const val SENDER_ID = "streampack_sender_id"

        /**
         * Message header: true when the provenance's principal came from a credential (a web token,
         * say) and must be re-read from the user store as the message enters the operation chain,
         * so a role or status change since the credential was issued, or while the message was
         * queued, is what the operations see (#115).
         */
        const val LIVE_AUTHORITY = "streampack_live_authority"

        /**
         * Message header: true when the sender is waiting on the egress stream for an answer, so a
         * command that fails unexpectedly is answered with a sanitized error rather than only
         * logged (#115). Without it, failures keep their existing handling.
         */
        const val REPORT_FAILURES = "streampack_report_failures"
        private val DISCORD_SNOWFLAKE = Regex("\\d{15,25}")

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
