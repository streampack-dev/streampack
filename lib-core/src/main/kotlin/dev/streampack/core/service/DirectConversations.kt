/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import org.springframework.stereotype.Component

/**
 * A protocol module's say in whether a provenance is a direct conversation, for what the address
 * alone can't tell (a Mattermost channel id says nothing of its type).
 */
interface DirectConversationDetector {
    fun isDirect(provenance: Provenance): Boolean
}

/**
 * Whether a provenance is a direct conversation: a private message, a DM or a group DM. What's said
 * in one is logged, marked direct, and never read back out of the log by anything.
 *
 * The rules for the address forms live here, not in the protocol modules, so they hold whether or
 * not a protocol is enabled (a `tell` to an IRC nick is logged even with IRC off).
 */
@Component
class DirectConversations(private val detectors: List<DirectConversationDetector>) {

    fun isDirect(provenance: Provenance): Boolean =
        when (provenance.protocol) {
            // A channel starts with one of IRC's channel prefixes; anything else is a nick
            Protocol.IRC ->
                provenance.replyTo.firstOrNull()?.let { it !in IRC_CHANNEL_PREFIXES } ?: true
            // Guild channels carry the guild; DMs have none
            Protocol.DISCORD -> provenance.serviceId == null
            // A DM is answered to the user's id; a group DM is known by its channel type
            Protocol.SLACK ->
                SLACK_USER_ID.matches(provenance.replyTo) ||
                    provenance.metadata[CHANNEL_TYPE] in SLACK_DIRECT_TYPES
            Protocol.MATTERMOST -> provenance.metadata[CHANNEL_TYPE] in MATTERMOST_DIRECT_TYPES
            else -> false
        } || detectors.any { it.isDirect(provenance) }

    companion object {
        /** The metadata key adapters put a conversation's protocol-specific type under */
        const val CHANNEL_TYPE = "channelType"
        private const val IRC_CHANNEL_PREFIXES = "#&+!"
        private val SLACK_USER_ID = Regex("[UW][A-Z0-9]{2,}")
        private val SLACK_DIRECT_TYPES = setOf("im", "mpim")
        val MATTERMOST_DIRECT_TYPES = setOf("D", "G")
    }
}
