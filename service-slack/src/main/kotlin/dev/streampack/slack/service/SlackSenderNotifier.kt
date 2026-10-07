/* Joseph B. Ottinger (C)2026 */
package dev.streampack.slack.service

import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.SenderNotifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * A DM to the sender, in the workspace the message came from (#148). Posting to a user id opens the
 * bot's DM with them, which is how DM replies are already sent.
 */
@Component
@ConditionalOnProperty("streampack.slack.enabled", havingValue = "true")
class SlackSenderNotifier(private val connectionManager: SlackConnectionManager) : SenderNotifier {
    override val protocol = Protocol.SLACK

    override fun notifySender(provenance: Provenance, senderId: String, text: String): Boolean {
        val adapter = provenance.serviceId?.let { connectionManager.getAdapter(it) } ?: return false
        adapter.sendMessage(senderId, text)
        return true
    }
}
