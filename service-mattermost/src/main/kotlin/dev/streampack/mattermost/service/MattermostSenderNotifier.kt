/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mattermost.service

import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.SenderNotifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * A DM to the sender's user id, on the server the message came from (#148), through the same direct
 * channel sign-in codes use.
 */
@Component
@ConditionalOnProperty("streampack.mattermost.enabled", havingValue = "true")
class MattermostSenderNotifier(private val connectionManager: MattermostConnectionManager) :
    SenderNotifier {
    override val protocol = Protocol.MATTERMOST

    override fun notifySender(provenance: Provenance, senderId: String, text: String): Boolean {
        val adapter = provenance.serviceId?.let { connectionManager.getAdapter(it) } ?: return false
        return adapter.sendDirectMessage(senderId, text)
    }
}
