/* Joseph B. Ottinger (C)2026 */
package dev.streampack.slack.service

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.ChannelControlService
import dev.streampack.core.service.ProtocolAdapter
import dev.streampack.core.service.SecretRefEnvironment
import dev.streampack.core.service.UserResolutionService
import dev.streampack.slack.config.SlackProperties
import dev.streampack.slack.entity.SlackWorkspace
import dev.streampack.slack.repository.SlackChannelRepository
import dev.streampack.slack.repository.SlackWorkspaceRepository
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * Manages active Slack workspace connections. Only instantiated when streampack.slack.enabled=true.
 * Reads autoconnect workspaces on startup and maintains a live adapter per connected workspace.
 */
@Component
@ConditionalOnProperty("streampack.slack.enabled", havingValue = "true")
class SlackConnectionManager(
    @Suppress("unused") private val secretRefStartupGuard: SlackSecretRefStartupGuard,
    private val eventGateway: EventGateway,
    private val userResolutionService: UserResolutionService,
    private val channelControlService: ChannelControlService,
    private val springEnvironment: Environment,
    private val slackProperties: SlackProperties,
    private val workspaceRepository: SlackWorkspaceRepository,
    private val channelRepository: SlackChannelRepository,
) : InitializingBean, DisposableBean, ProtocolAdapter {
    override val protocol: Protocol = Protocol.SLACK
    override val serviceName: String = "slack"

    override fun wouldTriggerIngress(text: String): Boolean = false

    override fun sendReply(provenance: Provenance, text: String) {
        // Individual per-workspace SlackAdapters handle message delivery
    }

    private val logger = LoggerFactory.getLogger(SlackConnectionManager::class.java)
    private val adapters = ConcurrentHashMap<String, SlackAdapter>()

    override fun afterPropertiesSet() {
        val autoconnectWorkspaces = workspaceRepository.findByAutoconnectTrueAndDeletedFalse()
        logger.info(
            "SlackConnectionManager started, found {} autoconnect workspace(s)",
            autoconnectWorkspaces.size,
        )
        for (workspace in autoconnectWorkspaces) {
            logger.info("Auto-connecting to Slack workspace '{}'", workspace.name)
            connect(workspace)
        }
    }

    override fun destroy() {
        logger.info("Shutting down all Slack connections")
        for ((name, adapter) in adapters) {
            logger.info("Disconnecting from Slack workspace '{}'", name)
            adapter.disconnect()
        }
        adapters.clear()
    }

    /** Creates a SlackAdapter and connects to the workspace via Socket Mode */
    fun connect(workspace: SlackWorkspace) {
        if (adapters.containsKey(workspace.name)) {
            logger.warn("Already connected to Slack workspace '{}'", workspace.name)
            return
        }

        val effectiveSignal = workspace.signalCharacter ?: slackProperties.signalCharacter
        val botToken =
            SecretRefEnvironment.resolve(workspace.botToken) { key ->
                System.getenv(key) ?: springEnvironment.getProperty(key)
            }
        val appToken =
            SecretRefEnvironment.resolve(workspace.appToken) { key ->
                System.getenv(key) ?: springEnvironment.getProperty(key)
            }
        val adapter =
            SlackAdapter(
                workspaceName = workspace.name,
                botToken = botToken,
                appToken = appToken,
                signalCharacter = effectiveSignal,
                eventGateway = eventGateway,
                userResolutionService = userResolutionService,
                channelControlService = channelControlService,
            )
        adapters[workspace.name] = adapter
        adapter.connect()
    }

    fun disconnect(workspaceName: String) {
        val adapter = adapters.remove(workspaceName)
        if (adapter != null) {
            adapter.disconnect()
            logger.info("Disconnected from Slack workspace '{}'", workspaceName)
        }
    }

    /** Returns the adapter for the given workspace, or null if not connected */
    fun getAdapter(workspaceName: String): SlackAdapter? = adapters[workspaceName]

    fun isConnected(workspaceName: String): Boolean = adapters.containsKey(workspaceName)

    /** Returns status summary for a specific workspace or all workspaces */
    fun getStatus(workspaceName: String?): String {
        if (workspaceName != null) {
            val adapter = adapters[workspaceName]
            return if (adapter != null) {
                "Workspace '$workspaceName': connected"
            } else {
                "Workspace '$workspaceName': not connected"
            }
        }

        if (adapters.isEmpty()) {
            return "No active Slack connections"
        }

        return adapters.keys.joinToString("\n") { name -> "  $name: connected" }
    }
}
