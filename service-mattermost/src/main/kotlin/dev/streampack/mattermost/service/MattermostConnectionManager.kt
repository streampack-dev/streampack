/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mattermost.service

import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.ProtocolAdapter
import dev.streampack.core.service.SecretRefEnvironment
import dev.streampack.core.service.UserResolutionService
import dev.streampack.mattermost.config.MattermostProperties
import dev.streampack.mattermost.entity.MattermostServer
import dev.streampack.mattermost.repository.MattermostServerRepository
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * Manages active Mattermost server connections.
 *
 * Only instantiated when `streampack.mattermost.enabled=true`.
 */
@Component
@ConditionalOnProperty("streampack.mattermost.enabled", havingValue = "true")
@EnableConfigurationProperties(MattermostProperties::class)
class MattermostConnectionManager(
    @Suppress("unused") private val secretRefStartupGuard: MattermostSecretRefStartupGuard,
    private val eventGateway: dev.streampack.core.integration.EventGateway,
    private val userResolutionService: UserResolutionService,
    private val springEnvironment: Environment,
    private val mattermostProperties: MattermostProperties,
    private val serverRepository: MattermostServerRepository,
    private val restClientBuilder: RestClient.Builder,
) : InitializingBean, DisposableBean, ProtocolAdapter {
    override val protocol: Protocol = Protocol.MATTERMOST
    override val serviceName: String = "mattermost"

    private val logger = LoggerFactory.getLogger(MattermostConnectionManager::class.java)
    private val adapters = ConcurrentHashMap<String, MattermostAdapter>()

    override fun afterPropertiesSet() {
        val autoconnectServers = serverRepository.findByAutoconnectTrueAndDeletedFalse()
        logger.info(
            "MattermostConnectionManager started, found {} autoconnect server(s)",
            autoconnectServers.size,
        )
        for (server in autoconnectServers) {
            /* One unreachable server must not stop the application, or the other servers */
            try {
                connect(server)
            } catch (e: Exception) {
                logger.warn("Autoconnect to Mattermost '{}' failed: {}", server.name, e.message)
            }
        }
    }

    override fun destroy() {
        logger.info("Shutting down all Mattermost connections")
        adapters.values.forEach { it.disconnect() }
        adapters.clear()
    }

    override fun wouldTriggerIngress(text: String): Boolean = false

    override fun sendReply(provenance: Provenance, text: String) {
        // Per-server MattermostAdapter instances perform actual delivery.
    }

    fun connect(server: MattermostServer) {
        if (adapters.containsKey(server.name)) {
            logger.warn("Already connected to Mattermost server '{}'", server.name)
            return
        }

        val token =
            SecretRefEnvironment.resolve(server.token) { key ->
                System.getenv(key) ?: springEnvironment.getProperty(key)
            }
        val signalCharacter = server.signalCharacter ?: mattermostProperties.signalCharacter
        val adapter =
            MattermostAdapter(
                serverName = server.name,
                baseUrl = server.baseUrl,
                token = token,
                initialSignalCharacter = signalCharacter,
                reconnectDelay = mattermostProperties.reconnectDelay,
                eventGateway = eventGateway,
                userResolutionService = userResolutionService,
                restClientBuilder = restClientBuilder,
            )
        adapters[server.name] = adapter
        try {
            adapter.connect()
        } catch (e: Exception) {
            /* Keep the adapter so status reports it and the backoff loop keeps trying */
            logger.warn("Connect to Mattermost '{}' failed, will retry: {}", server.name, e.message)
            adapter.retryLater()
        }
    }

    /** Applies a per-server signal override (null = global default) to a live adapter */
    fun updateSignal(serverName: String, override: String?) {
        adapters[serverName]?.signalCharacter = override ?: mattermostProperties.signalCharacter
    }

    fun disconnect(serverName: String) {
        adapters.remove(serverName)?.disconnect()
    }

    fun getAdapter(serverName: String): MattermostAdapter? = adapters[serverName]

    fun getStatus(serverName: String?): String {
        if (serverName != null) {
            val adapter = adapters[serverName]
            return if (adapter != null && adapter.isConnected()) {
                "Server '$serverName': connected"
            } else {
                "Server '$serverName': not connected"
            }
        }

        if (adapters.isEmpty()) return "No active Mattermost connections"
        return adapters.entries
            .sortedBy { it.key }
            .joinToString("\n") { (name, adapter) ->
                "  $name: " + if (adapter.isConnected()) "connected" else "reconnecting"
            }
    }
}
