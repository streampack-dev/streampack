/* Joseph B. Ottinger (C)2026 */
package dev.streampack.slack.service

import dev.streampack.core.model.SecretRef
import dev.streampack.core.service.ChannelControlService
import dev.streampack.slack.entity.SlackChannel
import dev.streampack.slack.entity.SlackWorkspace
import dev.streampack.slack.repository.SlackChannelRepository
import dev.streampack.slack.repository.SlackWorkspaceRepository
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

/**
 * Entity CRUD for Slack workspaces and channels. Delegates runtime operations (connect, mute) to
 * SlackConnectionManager when available. Usable in tests without a live Slack connection.
 */
@Component
class SlackService(
    private val workspaceRepository: SlackWorkspaceRepository,
    private val channelRepository: SlackChannelRepository,
    private val channelControlService: ChannelControlService,
    private val connectionManager: ObjectProvider<SlackConnectionManager>,
) {
    private val logger = LoggerFactory.getLogger(SlackService::class.java)

    /** Connects to a workspace, creating or updating the entity as needed */
    fun connect(name: String, botToken: String? = null, appToken: String? = null): String {
        val existing = workspaceRepository.findByNameAndDeletedFalse(name)

        if (existing == null && (botToken == null || appToken == null)) {
            return "Error: Workspace '$name' not found and no tokens provided"
        }

        val workspace =
            if (existing != null && botToken != null && appToken != null) {
                connectionManager.ifAvailable { it.disconnect(name) }
                workspaceRepository
                    .save(
                        existing.copy(
                            botToken = SecretRef.literal(botToken),
                            appToken = SecretRef.literal(appToken),
                            updatedAt = Instant.now(),
                        )
                    )
                    .also { logger.info("Updated credentials for Slack workspace '{}'", name) }
            } else if (existing != null) {
                existing
            } else {
                /* A removed workspace keeps its row (and the name stays unique): restore it */
                val removed = workspaceRepository.findByName(name)
                workspaceRepository
                    .save(
                        removed?.copy(
                            botToken = SecretRef.parse(botToken!!),
                            appToken = SecretRef.parse(appToken!!),
                            deleted = false,
                            autoconnect = false,
                            updatedAt = Instant.now(),
                        )
                            ?: SlackWorkspace(
                                name = name,
                                botToken = SecretRef.parse(botToken!!),
                                appToken = SecretRef.parse(appToken!!),
                            )
                    )
                    .also { logger.info("Registered Slack workspace '{}'", name) }
            }

        connectionManager.ifAvailable { it.connect(workspace) }
        return "Connecting to '$name'..."
    }

    /** Disconnects runtime adapter (workspace entity remains) */
    fun disconnect(name: String): String {
        if (workspaceRepository.findByNameAndDeletedFalse(name) == null) {
            return "Error: Workspace '$name' not found"
        }
        connectionManager.ifAvailable { it.disconnect(name) }
        return "Disconnected from '$name'"
    }

    /** Updates the autoconnect flag on a workspace */
    fun setAutoconnect(name: String, enabled: Boolean): String {
        val workspace =
            workspaceRepository.findByNameAndDeletedFalse(name)
                ?: return "Error: Workspace '$name' not found"
        workspaceRepository.save(workspace.copy(autoconnect = enabled, updatedAt = Instant.now()))
        return "Workspace '$name' autoconnect set to $enabled"
    }

    /**
     * Registers a channel and puts the bot in it. Connected, the channel is found by name (`#java`)
     * or id and, if public, joined; a private one has to have the bot invited first. Not connected,
     * only an id is taken: the channel is registered, and joined on connect if autojoin is on.
     *
     * Settings are kept by the channel's id, which is how its messages arrive.
     */
    fun join(workspaceName: String, channel: String): String {
        val workspace =
            workspaceRepository.findByNameAndDeletedFalse(workspaceName)
                ?: return "Error: Workspace '$workspaceName' not found"
        val adapter = connectionManager.ifAvailable?.getAdapter(workspaceName)
        val registered = findRegistered(workspace, channel)

        val ref =
            if (adapter != null) {
                adapter.resolveChannel(registered?.channelId ?: channel)
                    ?: return "Error: Channel '$channel' not found on '$workspaceName' " +
                        "(a private channel needs the bot invited first)"
            } else {
                val id =
                    registered?.channelId
                        ?: channel.takeIf { SlackConversationRef.looksLikeId(it) }
                        ?: return "Error: Workspace '$workspaceName' isn't connected: connect it " +
                            "to join '$channel' by name, or join by channel id"
                SlackConversationRef(id = id, isPrivate = false)
            }

        val name = ref.name?.let { "#$it" } ?: registered?.name ?: channel
        val saved =
            channelRepository.save(
                (registered ?: SlackChannel(workspace = workspace, name = name)).copy(
                    name = name,
                    channelId = ref.id,
                    updatedAt = Instant.now(),
                )
            )
        channelControlService.getOrCreateOptions(saved.provenanceUri()!!, private = ref.isPrivate)
        logger.info("Registered '{}' ({}) on '{}'", name, ref.id, workspaceName)

        return when {
            adapter == null ->
                "Registered '$name' on '$workspaceName'; it's joined on connect when autojoin is on"
            ref.isPrivate -> "Registered '$name' on '$workspaceName'; invite the bot to it in Slack"
            adapter.joinChannel(ref.id) -> "Joined '$name' on '$workspaceName'"
            else -> "Error: Registered '$name' on '$workspaceName', but Slack refused the join"
        }
    }

    /** Takes the bot out of a channel; the channel stays registered, with its settings */
    fun leave(workspaceName: String, channel: String): String {
        val workspace =
            workspaceRepository.findByNameAndDeletedFalse(workspaceName)
                ?: return "Error: Workspace '$workspaceName' not found"
        val registered =
            findRegistered(workspace, channel)
                ?: return channelNotFoundError(workspaceName, channel)
        val adapter =
            connectionManager.ifAvailable?.getAdapter(workspaceName)
                ?: return "Error: Workspace '$workspaceName' isn't connected"
        val channelId = registered.channelId ?: return noIdError(workspaceName, registered.name)
        return if (adapter.leaveChannel(channelId)) {
            "Left '${registered.name}' on '$workspaceName'"
        } else {
            "Error: Slack refused to let the bot leave '${registered.name}' on '$workspaceName'"
        }
    }

    /** Updates the autojoin flag via ChannelControlOptions */
    fun setAutojoin(workspaceName: String, channel: String, enabled: Boolean): String =
        withChannelUri(workspaceName, channel) { uri ->
            channelControlService.setFlag(uri, "autojoin", enabled)
            "Channel '$channel' on '$workspaceName' autojoin set to $enabled"
        }

    /** Mutes a channel at runtime via ChannelControlOptions */
    fun mute(workspaceName: String, channel: String): String =
        withChannelUri(workspaceName, channel) { uri ->
            channelControlService.setFlag(uri, "automute", true)
            "Muted '$channel' on '$workspaceName'"
        }

    /** Unmutes a channel at runtime via ChannelControlOptions */
    fun unmute(workspaceName: String, channel: String): String =
        withChannelUri(workspaceName, channel) { uri ->
            channelControlService.setFlag(uri, "automute", false)
            "Unmuted '$channel' on '$workspaceName'"
        }

    /** Updates the automute flag via ChannelControlOptions */
    fun setAutomute(workspaceName: String, channel: String, enabled: Boolean): String =
        withChannelUri(workspaceName, channel) { uri ->
            channelControlService.setFlag(uri, "automute", enabled)
            "Channel '$channel' on '$workspaceName' automute set to $enabled"
        }

    /** Updates the visible flag via ChannelControlOptions */
    fun setVisible(workspaceName: String, channel: String, visible: Boolean): String =
        withChannelUri(workspaceName, channel) { uri ->
            channelControlService.setFlag(uri, "visible", visible)
            "Channel '$channel' on '$workspaceName' visible set to $visible"
        }

    /** Updates the logged flag via ChannelControlOptions */
    fun setLogged(workspaceName: String, channel: String, logged: Boolean): String =
        withChannelUri(workspaceName, channel) { uri ->
            channelControlService.setFlag(uri, "logged", logged)
            "Channel '$channel' on '$workspaceName' logged set to $logged"
        }

    /** Updates the moderated flag (abuse detection, #150) via ChannelControlOptions */
    fun setModerated(workspaceName: String, channel: String, moderated: Boolean): String =
        withChannelUri(workspaceName, channel) { uri ->
            channelControlService.setFlag(uri, "moderated", moderated)
            "Channel '$channel' on '$workspaceName' moderated set to $moderated"
        }

    /** Soft-deletes a workspace and its channels, disconnecting the runtime adapter if active */
    fun remove(name: String): String {
        val workspace =
            workspaceRepository.findByNameAndDeletedFalse(name)
                ?: return "Error: Workspace '$name' not found"
        connectionManager.ifAvailable { it.disconnect(name) }
        val channels = channelRepository.findByWorkspaceAndDeletedFalse(workspace)
        for (channel in channels) {
            channelRepository.save(channel.copy(deleted = true, updatedAt = Instant.now()))
        }
        workspaceRepository.save(workspace.copy(deleted = true, updatedAt = Instant.now()))
        logger.info("Removed Slack workspace '{}' and {} channel(s)", name, channels.size)
        return "Workspace '$name' removed"
    }

    /** Updates the per-workspace signal character override */
    fun setSignal(name: String, signalCharacter: String?): String {
        val workspace =
            workspaceRepository.findByNameAndDeletedFalse(name)
                ?: return "Error: Workspace '$name' not found"
        workspaceRepository.save(
            workspace.copy(signalCharacter = signalCharacter, updatedAt = Instant.now())
        )
        connectionManager.ifAvailable { it.updateSignal(name, signalCharacter) }
        return if (signalCharacter != null) {
            "Workspace '$name' signal character set to '$signalCharacter'"
        } else {
            "Workspace '$name' signal character reset to global default"
        }
    }

    /** Returns status summary for workspaces */
    fun status(workspaceName: String?): String {
        val cm = connectionManager.ifAvailable
        if (cm != null) {
            return cm.getStatus(workspaceName)
        }

        if (workspaceName != null) {
            val workspace =
                workspaceRepository.findByNameAndDeletedFalse(workspaceName)
                    ?: return "Workspace '$workspaceName' not found"
            val channels = channelRepository.findByWorkspaceAndDeletedFalse(workspace)
            return "${workspace.toSummary()}, channels: ${channels.map { it.name }}"
        }

        val workspaces = workspaceRepository.findByDeletedFalse()
        if (workspaces.isEmpty()) return "No Slack workspaces configured"
        return workspaces.joinToString("\n") { "  ${it.toSummary()}" }
    }

    /** A registered channel, by id, or by name with or without its `#` */
    private fun findRegistered(workspace: SlackWorkspace, channel: String): SlackChannel? {
        val bare = channel.removePrefix("#")
        return channelRepository.findByWorkspaceAndChannelIdAndDeletedFalse(workspace, channel)
            ?: channelRepository.findByWorkspaceAndNameAndDeletedFalse(workspace, "#$bare")
            ?: channelRepository.findByWorkspaceAndNameAndDeletedFalse(workspace, bare)
    }

    /** Runs [action] with a registered channel's provenance URI, or says why there is none */
    private fun withChannelUri(
        workspaceName: String,
        channel: String,
        action: (String) -> String,
    ): String {
        val workspace =
            workspaceRepository.findByNameAndDeletedFalse(workspaceName)
                ?: return "Error: Workspace '$workspaceName' not found"
        val registered =
            findRegistered(workspace, channel)
                ?: return channelNotFoundError(workspaceName, channel)
        val uri = registered.provenanceUri() ?: return noIdError(workspaceName, registered.name)
        return action(uri)
    }

    private fun noIdError(workspaceName: String, channelName: String): String =
        "Error: '$channelName' on '$workspaceName' has no Slack id yet: " +
            "run 'slack join $workspaceName $channelName' while connected"

    private fun channelNotFoundError(workspaceName: String, channelName: String): String =
        "Error: Channel '$channelName' not found on '$workspaceName'"
}
