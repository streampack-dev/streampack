/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mattermost.service

import dev.streampack.core.model.SecretRef
import dev.streampack.core.service.ChannelControlService
import dev.streampack.mattermost.entity.MattermostChannel
import dev.streampack.mattermost.entity.MattermostServer
import dev.streampack.mattermost.model.MattermostChannelRef
import dev.streampack.mattermost.repository.MattermostChannelRepository
import dev.streampack.mattermost.repository.MattermostServerRepository
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

/** Entity CRUD for Mattermost servers and channels plus runtime delegation. */
@Component
class MattermostService(
    private val serverRepository: MattermostServerRepository,
    private val channelRepository: MattermostChannelRepository,
    private val channelControlService: ChannelControlService,
    private val connectionManager: ObjectProvider<MattermostConnectionManager>,
) {
    private val logger = LoggerFactory.getLogger(MattermostService::class.java)

    fun connect(name: String, baseUrl: String? = null, token: String? = null): String {
        val existing = serverRepository.findByNameAndDeletedFalse(name)

        if (existing == null && (baseUrl == null || token == null)) {
            return "Error: Server '$name' not found and no credentials provided"
        }

        val server =
            if (existing != null && baseUrl != null && token != null) {
                connectionManager.ifAvailable { it.disconnect(name) }
                serverRepository.save(
                    existing.copy(
                        baseUrl = normalizeBaseUrl(baseUrl),
                        token = SecretRef.literal(token),
                        updatedAt = Instant.now(),
                    )
                )
            } else if (existing != null) {
                existing
            } else {
                serverRepository.save(
                    MattermostServer(
                        name = name,
                        baseUrl = normalizeBaseUrl(baseUrl!!),
                        token = SecretRef.literal(token!!),
                    )
                )
            }

        return runCatching {
                connectionManager.ifAvailable { it.connect(server) }
                "Connecting to '$name'..."
            }
            .getOrElse { "Error: ${it.message ?: "Failed to connect to '$name'"}" }
    }

    fun disconnect(name: String): String {
        if (serverRepository.findByNameAndDeletedFalse(name) == null) {
            return "Error: Server '$name' not found"
        }
        connectionManager.ifAvailable { it.disconnect(name) }
        return "Disconnected from '$name'"
    }

    fun setAutoconnect(name: String, enabled: Boolean): String {
        val server =
            serverRepository.findByNameAndDeletedFalse(name)
                ?: return "Error: Server '$name' not found"
        serverRepository.save(server.copy(autoconnect = enabled, updatedAt = Instant.now()))
        return "Server '$name' autoconnect set to $enabled"
    }

    fun join(serverName: String, channelQuery: String): String {
        val server =
            serverRepository.findByNameAndDeletedFalse(serverName)
                ?: return "Error: Server '$serverName' not found"

        val channel = resolveChannel(serverName, channelQuery)
        if (channel == null) {
            return "Error: Could not resolve channel '$channelQuery' on '$serverName'"
        }

        val existing =
            channelRepository.findByServerAndChannelIdAndDeletedFalse(server, channel.id)
                ?: channelRepository.findByServerAndNameAndDeletedFalse(server, channel.name)
        val persisted =
            if (existing != null) {
                channelRepository.save(
                    existing.copy(
                        name = channel.name,
                        channelId = channel.id,
                        teamId = channel.teamId,
                        channelType = channel.type,
                        updatedAt = Instant.now(),
                    )
                )
            } else {
                channelRepository.save(
                    MattermostChannel(
                        server = server,
                        name = channel.name,
                        channelId = channel.id,
                        teamId = channel.teamId,
                        channelType = channel.type,
                    )
                )
            }

        channelControlService.getOrCreateOptions(persisted.provenanceUri())
        logger.info("Registered Mattermost channel '{}' on '{}'", persisted.name, serverName)
        return "Joined '${persisted.name}' on '$serverName'"
    }

    fun leave(serverName: String, channelQuery: String): String {
        val server =
            serverRepository.findByNameAndDeletedFalse(serverName)
                ?: return "Error: Server '$serverName' not found"
        val channel =
            channelRepository.findByServerAndNameAndDeletedFalse(server, channelQuery)
                ?: channelRepository.findByServerAndChannelIdAndDeletedFalse(server, channelQuery)
                ?: return "Error: Channel '$channelQuery' not found on '$serverName'"
        channelRepository.save(channel.copy(deleted = true, updatedAt = Instant.now()))
        return "Left '${channel.name}' on '$serverName'"
    }

    fun listChannels(serverName: String, searchTerm: String?): String {
        val server =
            serverRepository.findByNameAndDeletedFalse(serverName)
                ?: return "Error: Server '$serverName' not found"
        val adapter =
            connectionManager.ifAvailable?.getAdapter(server.name)
                ?: return "Error: Server '$serverName' is not connected"
        val channels = adapter.listChannels(searchTerm)
        if (channels.isEmpty()) return "No Mattermost channels found"
        return channels.joinToString("\n") { "  ${it.summary()}" }
    }

    fun setAutojoin(serverName: String, channelQuery: String, enabled: Boolean): String {
        val uri =
            resolveChannelUri(serverName, channelQuery)
                ?: return channelNotFoundError(serverName, channelQuery)
        channelControlService.setFlag(uri, "autojoin", enabled)
        return "Channel '$channelQuery' on '$serverName' autojoin set to $enabled"
    }

    fun mute(serverName: String, channelQuery: String): String {
        val uri =
            resolveChannelUri(serverName, channelQuery)
                ?: return channelNotFoundError(serverName, channelQuery)
        channelControlService.setFlag(uri, "automute", true)
        return "Muted '$channelQuery' on '$serverName'"
    }

    fun unmute(serverName: String, channelQuery: String): String {
        val uri =
            resolveChannelUri(serverName, channelQuery)
                ?: return channelNotFoundError(serverName, channelQuery)
        channelControlService.setFlag(uri, "automute", false)
        return "Unmuted '$channelQuery' on '$serverName'"
    }

    fun setAutomute(serverName: String, channelQuery: String, enabled: Boolean): String {
        val uri =
            resolveChannelUri(serverName, channelQuery)
                ?: return channelNotFoundError(serverName, channelQuery)
        channelControlService.setFlag(uri, "automute", enabled)
        return "Channel '$channelQuery' on '$serverName' automute set to $enabled"
    }

    fun setVisible(serverName: String, channelQuery: String, visible: Boolean): String {
        val uri =
            resolveChannelUri(serverName, channelQuery)
                ?: return channelNotFoundError(serverName, channelQuery)
        channelControlService.setFlag(uri, "visible", visible)
        return "Channel '$channelQuery' on '$serverName' visible set to $visible"
    }

    fun setLogged(serverName: String, channelQuery: String, logged: Boolean): String {
        val uri =
            resolveChannelUri(serverName, channelQuery)
                ?: return channelNotFoundError(serverName, channelQuery)
        channelControlService.setFlag(uri, "logged", logged)
        return "Channel '$channelQuery' on '$serverName' logged set to $logged"
    }

    fun remove(name: String): String {
        val server =
            serverRepository.findByNameAndDeletedFalse(name)
                ?: return "Error: Server '$name' not found"
        connectionManager.ifAvailable { it.disconnect(name) }
        val channels = channelRepository.findByServerAndDeletedFalse(server)
        channels.forEach {
            channelRepository.save(it.copy(deleted = true, updatedAt = Instant.now()))
        }
        serverRepository.save(server.copy(deleted = true, updatedAt = Instant.now()))
        return "Server '$name' removed"
    }

    fun setSignal(name: String, signalCharacter: String?): String {
        val server =
            serverRepository.findByNameAndDeletedFalse(name)
                ?: return "Error: Server '$name' not found"
        serverRepository.save(
            server.copy(signalCharacter = signalCharacter, updatedAt = Instant.now())
        )
        return if (signalCharacter != null) {
            "Server '$name' signal character set to '$signalCharacter'"
        } else {
            "Server '$name' signal character reset to global default"
        }
    }

    fun status(serverName: String?): String {
        val cm = connectionManager.ifAvailable
        if (cm != null) return cm.getStatus(serverName)

        if (serverName != null) {
            val server =
                serverRepository.findByNameAndDeletedFalse(serverName)
                    ?: return "Server '$serverName' not found"
            val channels = channelRepository.findByServerAndDeletedFalse(server)
            return "${server.toSummary()}, channels: ${channels.map { it.name }}"
        }

        val servers = serverRepository.findByDeletedFalse()
        if (servers.isEmpty()) return "No Mattermost servers configured"
        return servers.joinToString("\n") { "  ${it.toSummary()}" }
    }

    private fun resolveChannel(serverName: String, channelQuery: String): MattermostChannelRef? {
        val adapter = connectionManager.ifAvailable?.getAdapter(serverName)
        if (adapter != null) return adapter.resolveChannel(channelQuery)

        val explicitId = channelQuery.removePrefix("#").trim()
        return explicitId.takeIf(::looksLikeChannelId)?.let {
            MattermostChannelRef(id = it, name = it)
        }
    }

    private fun resolveChannelUri(serverName: String, channelQuery: String): String? {
        val server = serverRepository.findByNameAndDeletedFalse(serverName) ?: return null
        val channel =
            channelRepository.findByServerAndNameAndDeletedFalse(server, channelQuery)
                ?: channelRepository.findByServerAndChannelIdAndDeletedFalse(server, channelQuery)
                ?: return null
        return channel.provenanceUri()
    }

    private fun normalizeBaseUrl(baseUrl: String): String = baseUrl.trim().trimEnd('/')

    private fun looksLikeChannelId(value: String): Boolean = value.matches(Regex("[a-z0-9]{20,32}"))

    private fun channelNotFoundError(serverName: String, channelQuery: String): String =
        "Error: Channel '$channelQuery' not found on '$serverName'"
}
