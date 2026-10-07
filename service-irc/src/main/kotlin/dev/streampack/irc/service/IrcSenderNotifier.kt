/* Joseph B. Ottinger (C)2026 */
package dev.streampack.irc.service

import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.SenderNotifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/** A private message to the sender's nick, on the network the message came from (#148) */
@Component
@ConditionalOnProperty("streampack.irc.enabled", havingValue = "true")
class IrcSenderNotifier(private val connectionManager: IrcConnectionManager) : SenderNotifier {
    override val protocol = Protocol.IRC

    override fun notifySender(provenance: Provenance, senderId: String, text: String): Boolean {
        val adapter = provenance.serviceId?.let { connectionManager.getAdapter(it) } ?: return false
        adapter.sendMessage(senderId, text)
        return true
    }
}
