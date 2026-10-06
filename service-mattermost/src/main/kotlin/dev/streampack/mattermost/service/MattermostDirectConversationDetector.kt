/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mattermost.service

import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.DirectConversationDetector
import dev.streampack.core.service.DirectConversations
import dev.streampack.mattermost.repository.MattermostChannelRepository
import dev.streampack.mattermost.repository.MattermostServerRepository
import org.springframework.stereotype.Component

/**
 * A Mattermost channel id doesn't say what kind of channel it is. A post carries its channel's
 * type, which [DirectConversations] reads; a message with none (a reply sent on its own, such as a
 * `tell`) is direct if the channel is registered as a direct or group message.
 */
@Component
class MattermostDirectConversationDetector(
    private val serverRepository: MattermostServerRepository,
    private val channelRepository: MattermostChannelRepository,
) : DirectConversationDetector {

    override fun isDirect(provenance: Provenance): Boolean {
        if (provenance.protocol != Protocol.MATTERMOST) return false
        if (provenance.metadata.containsKey(DirectConversations.CHANNEL_TYPE)) return false
        val server =
            provenance.serviceId?.let { serverRepository.findByNameAndDeletedFalse(it) }
                ?: return false
        val channel =
            channelRepository.findByServerAndChannelIdAndDeletedFalse(server, provenance.replyTo)
                ?: return false
        return channel.channelType in DirectConversations.MATTERMOST_DIRECT_TYPES
    }
}
