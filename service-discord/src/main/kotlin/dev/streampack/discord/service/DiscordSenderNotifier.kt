/* Joseph B. Ottinger (C)2026 */
package dev.streampack.discord.service

import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.SenderNotifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/** A DM to the sender's user id, as `discord:///<userId>` replies are sent (#148) */
@Component
@ConditionalOnProperty("streampack.discord.enabled", havingValue = "true")
class DiscordSenderNotifier(private val discordAdapter: DiscordAdapter) : SenderNotifier {
    override val protocol = Protocol.DISCORD

    override fun notifySender(provenance: Provenance, senderId: String, text: String): Boolean {
        discordAdapter.sendPrivateMessage(senderId, text)
        return true
    }
}
