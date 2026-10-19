/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mattermost.service

import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.DirectConversations
import dev.streampack.core.service.TellTargetResolution
import dev.streampack.core.service.TellTargetResolver
import dev.streampack.mattermost.repository.MattermostServerRepository
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

/**
 * Reads a `tell` target on Mattermost (#262). Mattermost posts only to a channel id, so a name is
 * found on the server's live connection:
 * - `@name` is the user's direct-message channel with the bot, opened if it isn't open yet.
 * - Any other name is a channel: its URL name (`foo-bar`), `#foo-bar`, or its display name (`"foo
 *   bar"`), as `mattermost channels` finds it.
 * - A channel id is used as it is, connected or not.
 *
 * The id of a direct-message channel is never put in a refusal, so no reply shows it.
 */
@Component
class MattermostTellTargets(
    private val serverRepository: MattermostServerRepository,
    private val connectionManager: ObjectProvider<MattermostConnectionManager>,
) : TellTargetResolver {

    override val protocol: Protocol = Protocol.MATTERMOST

    override fun resolve(target: Provenance): TellTargetResolution {
        val given = target.replyTo.trim()
        if (CHANNEL_ID.matches(given)) return TellTargetResolution.Resolved(target)

        val serverName =
            target.serviceId?.takeIf { it.isNotBlank() }
                ?: return refused(
                    "A Mattermost address needs its server: mattermost://<server>/$given",
                )
        val server =
            serverRepository.findByNameAndDeletedFalse(serverName)
                ?: return refused("There's no Mattermost server '$serverName'.")
        val adapter =
            connectionManager.ifAvailable?.getAdapter(server.name)?.takeIf { it.isConnected() }
                ?: return refused(
                    "'${server.name}' isn't connected, so I can't look up '$given'. A channel id works.",
                )

        return if (given.startsWith("@")) direct(adapter, target, given)
        else channel(adapter, target, given)
    }

    /** The DM channel with the user [given] names, marked direct so the log keeps it private */
    private fun direct(
        adapter: MattermostAdapter,
        target: Provenance,
        given: String,
    ): TellTargetResolution {
        val username = given.removePrefix("@")
        val user =
            adapter.lookupUser(username)?.takeIf { it.id.isNotBlank() }
                ?: return refused("There's no Mattermost user @$username on '${target.serviceId}'.")
        val channelId =
            adapter.directChannelId(user.id)
                ?: return refused(
                    "I couldn't open a direct message with @$username on '${target.serviceId}'.",
                )
        return TellTargetResolution.Resolved(
            target.copy(
                replyTo = channelId,
                metadata = target.metadata + (DirectConversations.CHANNEL_TYPE to DIRECT),
            ),
        )
    }

    /** The channel [given] names, with its type, so the log knows a group DM from a channel */
    private fun channel(
        adapter: MattermostAdapter,
        target: Provenance,
        given: String,
    ): TellTargetResolution {
        val channel =
            runCatching { adapter.resolveChannel(given) }
                .getOrElse {
                    return refused(
                        (it as? IllegalArgumentException)?.message
                            ?: "I couldn't look up '$given' on '${target.serviceId}'.",
                    )
                } ?: return refused("There's no channel '$given' on '${target.serviceId}'.")
        val metadata =
            channel.type
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    target.metadata + (DirectConversations.CHANNEL_TYPE to it)
                } ?: target.metadata
        return TellTargetResolution.Resolved(target.copy(replyTo = channel.id, metadata = metadata))
    }

    private fun refused(reason: String) = TellTargetResolution.Refused(reason)

    private companion object {
        /** Mattermost's ids: 26 lower-case letters and digits; `@` in front always means a name */
        val CHANNEL_ID = Regex("[a-z0-9]{26}")

        /** Mattermost's type for a direct-message channel */
        const val DIRECT = "D"
    }
}
