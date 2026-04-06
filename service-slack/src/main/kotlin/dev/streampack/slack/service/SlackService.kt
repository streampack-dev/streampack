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
                workspaceRepository
                    .save(
                        SlackWorkspace(
                            name = name,
                            botToken = SecretRef.literal(botToken!!),
                            appToken = SecretRef.literal(appToken!!),
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

    /** Registers a channel if needed, creates ChannelControlOptions, and resolves channel ID */
    fun join(workspaceName: String, channelName: String): String {
        val workspace =
            workspaceRepository.findByNameAndDeletedFalse(workspaceName)
                ?: return "Error: Workspace '$workspaceName' not found"
        var channel =
            channelRepository.findByWorkspaceAndNameAndDeletedFalse(workspace, channelName)
                ?: channelRepository
                    .save(SlackChannel(workspace = workspace, name = channelName))
                    .also {
                        logger.info("Registered channel '{}' on '{}'", channelName, workspaceName)
                    }

        channelControlService.getOrCreateOptions(channel.provenanceUri())

        // Resolve Slack channel ID if connected
        connectionManager.ifAvailable { cm ->
            val adapter = cm.getAdapter(workspaceName)
            if (adapter != null && channel.channelId == null) {
                val resolvedId = adapter.resolveChannelId(channelName)
                if (resolvedId != null) {
                    channel =
                        channelRepository.save(
                            channel.copy(channelId = resolvedId, updatedAt = Instant.now())
                        )
                    logger.info(
                        "Resolved channel ID for '{}' on '{}': {}",
                        channelName,
                        workspaceName,
                        resolvedId,
                    )
                }
            }
        }

        return "Joined '$channelName' on '$workspaceName'"
    }

    /** Leaves a channel at runtime (entity remains) */
    fun leave(workspaceName: String, channelName: String): String {
        val workspace =
            workspaceRepository.findByNameAndDeletedFalse(workspaceName)
                ?: return "Error: Workspace '$workspaceName' not found"
        if (
            channelRepository.findByWorkspaceAndNameAndDeletedFalse(workspace, channelName) == null
        ) {
            return "Error: Channel '$channelName' not found on '$workspaceName'"
        }
        return "Left '$channelName' on '$workspaceName'"
    }

    /** Updates the autojoin flag via ChannelControlOptions */
    fun setAutojoin(workspaceName: String, channelName: String, enabled: Boolean): String {
        val uri =
            resolveChannelUri(workspaceName, channelName)
                ?: return channelNotFoundError(workspaceName, channelName)
        channelControlService.setFlag(uri, "autojoin", enabled)
        return "Channel '$channelName' on '$workspaceName' autojoin set to $enabled"
    }

    /** Mutes a channel at runtime via ChannelControlOptions */
    fun mute(workspaceName: String, channelName: String): String {
        val uri =
            resolveChannelUri(workspaceName, channelName)
                ?: return channelNotFoundError(workspaceName, channelName)
        channelControlService.setFlag(uri, "automute", true)
        return "Muted '$channelName' on '$workspaceName'"
    }

    /** Unmutes a channel at runtime via ChannelControlOptions */
    fun unmute(workspaceName: String, channelName: String): String {
        val uri =
            resolveChannelUri(workspaceName, channelName)
                ?: return channelNotFoundError(workspaceName, channelName)
        channelControlService.setFlag(uri, "automute", false)
        return "Unmuted '$channelName' on '$workspaceName'"
    }

    /** Updates the automute flag via ChannelControlOptions */
    fun setAutomute(workspaceName: String, channelName: String, enabled: Boolean): String {
        val uri =
            resolveChannelUri(workspaceName, channelName)
                ?: return channelNotFoundError(workspaceName, channelName)
        channelControlService.setFlag(uri, "automute", enabled)
        return "Channel '$channelName' on '$workspaceName' automute set to $enabled"
    }

    /** Updates the visible flag via ChannelControlOptions */
    fun setVisible(workspaceName: String, channelName: String, visible: Boolean): String {
        val uri =
            resolveChannelUri(workspaceName, channelName)
                ?: return channelNotFoundError(workspaceName, channelName)
        channelControlService.setFlag(uri, "visible", visible)
        return "Channel '$channelName' on '$workspaceName' visible set to $visible"
    }

    /** Updates the logged flag via ChannelControlOptions */
    fun setLogged(workspaceName: String, channelName: String, logged: Boolean): String {
        val uri =
            resolveChannelUri(workspaceName, channelName)
                ?: return channelNotFoundError(workspaceName, channelName)
        channelControlService.setFlag(uri, "logged", logged)
        return "Channel '$channelName' on '$workspaceName' logged set to $logged"
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

    /** Resolves a channel to its provenance URI, or null if not found */
    private fun resolveChannelUri(workspaceName: String, channelName: String): String? {
        val workspace = workspaceRepository.findByNameAndDeletedFalse(workspaceName) ?: return null
        val channel =
            channelRepository.findByWorkspaceAndNameAndDeletedFalse(workspace, channelName)
                ?: return null
        return channel.provenanceUri()
    }

    private fun channelNotFoundError(workspaceName: String, channelName: String): String =
        "Error: Channel '$channelName' not found on '$workspaceName'"
}
