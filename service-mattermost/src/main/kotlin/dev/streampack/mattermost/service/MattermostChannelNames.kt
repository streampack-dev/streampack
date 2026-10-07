/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mattermost.service

import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.ChannelNameProvider
import dev.streampack.mattermost.repository.MattermostChannelRepository
import dev.streampack.mattermost.repository.MattermostServerRepository
import org.springframework.stereotype.Component

/**
 * A Mattermost channel's name (its URL name, such as `town-square`), from its registered channel
 */
@Component
class MattermostChannelNames(
    private val serverRepository: MattermostServerRepository,
    private val channelRepository: MattermostChannelRepository,
) : ChannelNameProvider {
    override val protocol = Protocol.MATTERMOST

    override fun name(provenance: Provenance): String? {
        val server =
            provenance.serviceId?.let { serverRepository.findByNameAndDeletedFalse(it) }
                ?: return null
        return channelRepository
            .findByServerAndChannelIdAndDeletedFalse(server, provenance.replyTo)
            ?.name
            ?.removePrefix("#")
            ?.takeIf { it.isNotBlank() && it != provenance.replyTo }
    }
}
