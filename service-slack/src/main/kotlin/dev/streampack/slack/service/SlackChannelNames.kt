/* Joseph B. Ottinger (C)2026 */
package dev.streampack.slack.service

import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.ChannelNameProvider
import dev.streampack.slack.repository.SlackChannelRepository
import dev.streampack.slack.repository.SlackWorkspaceRepository
import org.springframework.stereotype.Component

/** A Slack channel's name, from the channel registered with its id */
@Component
class SlackChannelNames(
    private val workspaceRepository: SlackWorkspaceRepository,
    private val channelRepository: SlackChannelRepository,
) : ChannelNameProvider {
    override val protocol = Protocol.SLACK

    override fun name(provenance: Provenance): String? {
        val workspace =
            provenance.serviceId?.let { workspaceRepository.findByNameAndDeletedFalse(it) }
                ?: return null
        return channelRepository
            .findByWorkspaceAndChannelIdAndDeletedFalse(workspace, provenance.replyTo)
            ?.name
            ?.removePrefix("#")
            ?.takeIf { it.isNotBlank() && it != provenance.replyTo }
    }
}
