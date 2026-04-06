/* Joseph B. Ottinger (C)2026 */
package dev.streampack.irc.service

import dev.streampack.core.model.SecretRef
import dev.streampack.core.service.ChannelControlService
import dev.streampack.core.service.ProvenanceStateService
import dev.streampack.irc.entity.IrcChannel
import dev.streampack.irc.entity.IrcNetwork
import dev.streampack.irc.repository.IrcChannelRepository
import dev.streampack.irc.repository.IrcNetworkRepository
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

/**
 * Entity CRUD for IRC networks and channels. Delegates runtime operations (connect, join, mute) to
 * IrcConnectionManager when available. Usable in tests without a live IRC connection.
 */
@Component
class IrcService(
    private val networkRepository: IrcNetworkRepository,
    private val channelRepository: IrcChannelRepository,
    private val channelControlService: ChannelControlService,
    private val provenanceStateService: ProvenanceStateService,
    private val connectionManager: ObjectProvider<IrcConnectionManager>,
) {
    private val logger = LoggerFactory.getLogger(IrcService::class.java)

    /** Connects to a network, creating or updating the entity as needed */
    fun connect(
        name: String,
        host: String? = null,
        nick: String? = null,
        saslAccount: String? = null,
        saslPassword: String? = null,
    ): String {
        val existing = networkRepository.findByNameAndDeletedFalse(name)

        if (existing == null && (host == null || nick == null)) {
            return "Error: Network '$name' not found and no connection details provided"
        }

        val network =
            if (existing != null && host != null && nick != null) {
                connectionManager.ifAvailable { it.disconnect(name) }
                networkRepository
                    .save(
                        existing.copy(
                            host = host,
                            nick = nick,
                            saslAccount = saslAccount?.let { SecretRef.literal(it) },
                            saslPassword = saslPassword?.let { SecretRef.literal(it) },
                            updatedAt = Instant.now(),
                        )
                    )
                    .also { logger.info("Updated credentials for IRC network '{}'", name) }
            } else if (existing != null) {
                existing
            } else {
                networkRepository
                    .save(
                        IrcNetwork(
                            name = name,
                            host = host!!,
                            nick = nick!!,
                            saslAccount = saslAccount?.let { SecretRef.literal(it) },
                            saslPassword = saslPassword?.let { SecretRef.literal(it) },
                        )
                    )
                    .also { logger.info("Registered IRC network '{}'", name) }
            }

        connectionManager.ifAvailable { it.connect(network) }
        return "Connecting to '$name'..."
    }

    /** Disconnects runtime adapter (network entity remains) */
    fun disconnect(name: String): String {
        if (networkRepository.findByNameAndDeletedFalse(name) == null) {
            return "Error: Network '$name' not found"
        }
        connectionManager.ifAvailable { it.disconnect(name) }
        return "Disconnected from '$name'"
    }

    /** Updates the autoconnect flag on a network */
    fun setAutoconnect(name: String, enabled: Boolean): String {
        val network =
            networkRepository.findByNameAndDeletedFalse(name)
                ?: return "Error: Network '$name' not found"
        networkRepository.save(network.copy(autoconnect = enabled, updatedAt = Instant.now()))
        return "Network '$name' autoconnect set to $enabled"
    }

    /** Registers a channel if needed and joins it, creating default ChannelControlOptions */
    fun join(networkName: String, channelName: String): String {
        val network =
            networkRepository.findByNameAndDeletedFalse(networkName)
                ?: return "Error: Network '$networkName' not found"
        val channel =
            channelRepository.findByNetworkAndNameAndDeletedFalse(network, channelName)
                ?: channelRepository.save(IrcChannel(network = network, name = channelName)).also {
                    logger.info("Registered channel '{}' on '{}'", channelName, networkName)
                }
        channelControlService.getOrCreateOptions(channel.provenanceUri())
        connectionManager.ifAvailable { it.join(networkName, channelName) }
        return "Joined '$channelName' on '$networkName'"
    }

    /** Leaves a channel at runtime (entity remains) */
    fun leave(networkName: String, channelName: String): String {
        val network =
            networkRepository.findByNameAndDeletedFalse(networkName)
                ?: return "Error: Network '$networkName' not found"
        if (channelRepository.findByNetworkAndNameAndDeletedFalse(network, channelName) == null) {
            return "Error: Channel '$channelName' not found on '$networkName'"
        }
        connectionManager.ifAvailable { it.leave(networkName, channelName) }
        return "Left '$channelName' on '$networkName'"
    }

    /** Updates the autojoin flag via ChannelControlOptions */
    fun setAutojoin(networkName: String, channelName: String, enabled: Boolean): String {
        val uri =
            resolveChannelUri(networkName, channelName)
                ?: return channelNotFoundError(networkName, channelName)
        channelControlService.setFlag(uri, "autojoin", enabled)
        return "Channel '$channelName' on '$networkName' autojoin set to $enabled"
    }

    /** Mutes a channel at runtime via ChannelControlOptions */
    fun mute(networkName: String, channelName: String): String {
        val uri =
            resolveChannelUri(networkName, channelName)
                ?: return channelNotFoundError(networkName, channelName)
        channelControlService.setFlag(uri, "automute", true)
        return "Muted '$channelName' on '$networkName'"
    }

    /** Unmutes a channel at runtime via ChannelControlOptions */
    fun unmute(networkName: String, channelName: String): String {
        val uri =
            resolveChannelUri(networkName, channelName)
                ?: return channelNotFoundError(networkName, channelName)
        channelControlService.setFlag(uri, "automute", false)
        return "Unmuted '$channelName' on '$networkName'"
    }

    /** Updates the automute flag via ChannelControlOptions */
    fun setAutomute(networkName: String, channelName: String, enabled: Boolean): String {
        val uri =
            resolveChannelUri(networkName, channelName)
                ?: return channelNotFoundError(networkName, channelName)
        channelControlService.setFlag(uri, "automute", enabled)
        return "Channel '$channelName' on '$networkName' automute set to $enabled"
    }

    /** Updates the visible flag via ChannelControlOptions */
    fun setVisible(networkName: String, channelName: String, visible: Boolean): String {
        val uri =
            resolveChannelUri(networkName, channelName)
                ?: return channelNotFoundError(networkName, channelName)
        channelControlService.setFlag(uri, "visible", visible)
        return "Channel '$channelName' on '$networkName' visible set to $visible"
    }

    /** Updates the logged flag via ChannelControlOptions */
    fun setLogged(networkName: String, channelName: String, logged: Boolean): String {
        val uri =
            resolveChannelUri(networkName, channelName)
                ?: return channelNotFoundError(networkName, channelName)
        channelControlService.setFlag(uri, "logged", logged)
        return "Channel '$channelName' on '$networkName' logged set to $logged"
    }

    /** Configures whether the bot is allowed to hold ops in a channel */
    fun setAllowOps(networkName: String, channelName: String, enabled: Boolean): String {
        val uri =
            resolveChannelUri(networkName, channelName)
                ?: return channelNotFoundError(networkName, channelName)
        provenanceStateService.setState(uri, IrcAdapter.ALLOW_OPS_KEY, mapOf("enabled" to enabled))
        return "Channel '$channelName' on '$networkName' allow-ops set to $enabled"
    }

    /** Soft-deletes a network and its channels, disconnecting the runtime adapter if active */
    fun remove(name: String): String {
        val network =
            networkRepository.findByNameAndDeletedFalse(name)
                ?: return "Error: Network '$name' not found"
        connectionManager.ifAvailable { it.disconnect(name) }
        val channels = channelRepository.findByNetworkAndDeletedFalse(network)
        for (channel in channels) {
            channelRepository.save(channel.copy(deleted = true, updatedAt = Instant.now()))
        }
        networkRepository.save(network.copy(deleted = true, updatedAt = Instant.now()))
        logger.info("Removed IRC network '{}' and {} channel(s)", name, channels.size)
        return "Network '$name' removed"
    }

    /** Updates the per-network signal character override */
    fun setSignal(name: String, signalCharacter: String?): String {
        val network =
            networkRepository.findByNameAndDeletedFalse(name)
                ?: return "Error: Network '$name' not found"
        networkRepository.save(
            network.copy(signalCharacter = signalCharacter, updatedAt = Instant.now())
        )
        return if (signalCharacter != null) {
            "Network '$name' signal character set to '$signalCharacter'"
        } else {
            "Network '$name' signal character reset to global default"
        }
    }

    /** Returns status summary for networks */
    fun status(networkName: String?): String {
        val cm = connectionManager.ifAvailable
        if (cm != null) {
            return cm.getStatus(networkName)
        }

        if (networkName != null) {
            val network =
                networkRepository.findByNameAndDeletedFalse(networkName)
                    ?: return "Network '$networkName' not found"
            val channels = channelRepository.findByNetworkAndDeletedFalse(network)
            return "${network.toSummary()}, channels: ${channels.map { it.name }}"
        }

        val networks = networkRepository.findByDeletedFalse()
        if (networks.isEmpty()) return "No IRC networks configured"
        return networks.joinToString("\n") { "  ${it.toSummary()}" }
    }

    /** Resolves a channel to its provenance URI, or null if not found */
    private fun resolveChannelUri(networkName: String, channelName: String): String? {
        val network = networkRepository.findByNameAndDeletedFalse(networkName) ?: return null
        val channel =
            channelRepository.findByNetworkAndNameAndDeletedFalse(network, channelName)
                ?: return null
        return channel.provenanceUri()
    }

    private fun channelNotFoundError(networkName: String, channelName: String): String =
        "Error: Channel '$channelName' not found on '$networkName'"
}
