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
                        token = SecretRef.parse(token),
                        updatedAt = Instant.now(),
                    )
                )
            } else if (existing != null) {
                existing
            } else {
                /* A removed server keeps its row (and the name stays unique): restore it */
                val removed = serverRepository.findByName(name)
                serverRepository.save(
                    removed?.copy(
                        baseUrl = normalizeBaseUrl(baseUrl!!),
                        token = SecretRef.parse(token!!),
                        deleted = false,
                        autoconnect = false,
                        updatedAt = Instant.now(),
                    )
                        ?: MattermostServer(
                            name = name,
                            baseUrl = normalizeBaseUrl(baseUrl!!),
                            token = SecretRef.parse(token!!),
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

    /**
     * Register a channel and, for public channels, add the account to it so the server starts
     * sending its posts. Private channels and direct messages cannot be joined by the account
     * itself; they are registered and the operator is told to add the account on Mattermost.
     */
    fun join(serverName: String, channelQuery: String): String {
        val server =
            serverRepository.findByNameAndDeletedFalse(serverName)
                ?: return "Error: Server '$serverName' not found"

        val channel =
            try {
                resolveChannel(serverName, channelQuery)
            } catch (e: IllegalArgumentException) {
                return "Error: ${e.message}"
            } ?: return "Error: Could not resolve channel '$channelQuery' on '$serverName'"

        val persisted = registerChannel(server, channel)
        val adapter = connectionManager.ifAvailable?.getAdapter(serverName)
        val membership =
            when {
                adapter == null -> " (not connected; membership unchanged)"
                channel.type == "O" ->
                    if (adapter.joinChannel(channel.id)) ""
                    else " (could not join; add the account on Mattermost)"
                else -> " (private; add the account to the channel on Mattermost)"
            }
        return "Joined '${persisted.name}' on '$serverName'$membership"
    }

    /**
     * Persist a channel record keyed by its Mattermost id and create its controls. Private channels
     * and direct or group messages start hidden and unlogged.
     */
    fun registerChannel(
        server: MattermostServer,
        channel: MattermostChannelRef,
    ): MattermostChannel {
        val existing = channelRepository.findByServerAndChannelIdAndDeletedFalse(server, channel.id)
        val persisted =
            channelRepository.save(
                existing?.copy(
                    name = channel.name,
                    teamId = channel.teamId ?: existing.teamId,
                    channelType = channel.type ?: existing.channelType,
                    updatedAt = Instant.now(),
                )
                    ?: MattermostChannel(
                        server = server,
                        name = channel.name,
                        channelId = channel.id,
                        teamId = channel.teamId,
                        channelType = channel.type,
                    )
            )
        val private = channel.type != null && channel.type != "O"
        channelControlService.getOrCreateOptions(persisted.provenanceUri(), private = private)
        logger.info(
            "Registered Mattermost channel '{}' [{}] on '{}'{}",
            persisted.name,
            persisted.channelId,
            server.name,
            if (private) " (private)" else "",
        )
        return persisted
    }

    /** Leave a channel: the account is removed on Mattermost and the record retired */
    fun leave(serverName: String, channelQuery: String): String {
        val server =
            serverRepository.findByNameAndDeletedFalse(serverName)
                ?: return "Error: Server '$serverName' not found"
        val channel =
            findRegistered(server, channelQuery)
                ?: return "Error: Channel '$channelQuery' not found on '$serverName'"
        channelRepository.save(channel.copy(deleted = true, updatedAt = Instant.now()))
        val adapter = connectionManager.ifAvailable?.getAdapter(serverName)
        val membership =
            when {
                adapter == null -> " (not connected; membership unchanged)"
                adapter.leaveChannel(channel.channelId) -> ""
                else -> " (could not leave on Mattermost)"
            }
        return "Left '${channel.name}' on '$serverName'$membership"
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
            try {
                resolveChannelUri(serverName, channelQuery)
            } catch (e: AmbiguousChannelException) {
                return "Error: ${e.message}"
            } ?: return channelNotFoundError(serverName, channelQuery)
        channelControlService.setFlag(uri, "autojoin", enabled)
        return "Channel '$channelQuery' on '$serverName' autojoin set to $enabled"
    }

    fun mute(serverName: String, channelQuery: String): String {
        val uri =
            try {
                resolveChannelUri(serverName, channelQuery)
            } catch (e: AmbiguousChannelException) {
                return "Error: ${e.message}"
            } ?: return channelNotFoundError(serverName, channelQuery)
        channelControlService.setFlag(uri, "automute", true)
        return "Muted '$channelQuery' on '$serverName'"
    }

    fun unmute(serverName: String, channelQuery: String): String {
        val uri =
            try {
                resolveChannelUri(serverName, channelQuery)
            } catch (e: AmbiguousChannelException) {
                return "Error: ${e.message}"
            } ?: return channelNotFoundError(serverName, channelQuery)
        channelControlService.setFlag(uri, "automute", false)
        return "Unmuted '$channelQuery' on '$serverName'"
    }

    fun setAutomute(serverName: String, channelQuery: String, enabled: Boolean): String {
        val uri =
            try {
                resolveChannelUri(serverName, channelQuery)
            } catch (e: AmbiguousChannelException) {
                return "Error: ${e.message}"
            } ?: return channelNotFoundError(serverName, channelQuery)
        channelControlService.setFlag(uri, "automute", enabled)
        return "Channel '$channelQuery' on '$serverName' automute set to $enabled"
    }

    fun setVisible(serverName: String, channelQuery: String, visible: Boolean): String {
        val uri =
            try {
                resolveChannelUri(serverName, channelQuery)
            } catch (e: AmbiguousChannelException) {
                return "Error: ${e.message}"
            } ?: return channelNotFoundError(serverName, channelQuery)
        channelControlService.setFlag(uri, "visible", visible)
        return "Channel '$channelQuery' on '$serverName' visible set to $visible"
    }

    fun setLogged(serverName: String, channelQuery: String, logged: Boolean): String {
        val uri =
            try {
                resolveChannelUri(serverName, channelQuery)
            } catch (e: AmbiguousChannelException) {
                return "Error: ${e.message}"
            } ?: return channelNotFoundError(serverName, channelQuery)
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
        connectionManager.ifAvailable { it.updateSignal(name, signalCharacter) }
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
        return findRegistered(server, channelQuery)?.provenanceUri()
    }

    /**
     * A registered channel by id, else by name. A name shared by several teams is refused with the
     * candidate ids, rather than silently picking one.
     */
    private fun findRegistered(server: MattermostServer, query: String): MattermostChannel? {
        val cleaned = query.removePrefix("#").trim()
        channelRepository.findByServerAndChannelIdAndDeletedFalse(server, cleaned)?.let {
            return it
        }
        val byName = channelRepository.findByServerAndNameAndDeletedFalse(server, cleaned)
        if (byName.size > 1) {
            throw AmbiguousChannelException(
                "Channel '$cleaned' is registered on several teams; use the id: " +
                    byName.joinToString(", ") {
                        "${it.channelId}${it.teamId?.let { t -> " (team $t)" } ?: ""}"
                    }
            )
        }
        return byName.firstOrNull()
    }

    private fun normalizeBaseUrl(baseUrl: String): String = baseUrl.trim().trimEnd('/')

    private fun looksLikeChannelId(value: String): Boolean = value.matches(Regex("[a-z0-9]{20,32}"))

    private fun channelNotFoundError(serverName: String, channelQuery: String): String =
        "Error: Channel '$channelQuery' not found on '$serverName'"
}

/** A channel name that matches several registered channels; the message lists the candidate ids */
class AmbiguousChannelException(message: String) : IllegalArgumentException(message)
