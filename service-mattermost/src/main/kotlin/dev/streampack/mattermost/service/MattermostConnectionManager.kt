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
        autoconnectServers.forEach(::connect)
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
                signalCharacter = signalCharacter,
                reconnectDelay = mattermostProperties.reconnectDelay,
                eventGateway = eventGateway,
                userResolutionService = userResolutionService,
                restClientBuilder = restClientBuilder,
            )
        adapter.connect()
        adapters[server.name] = adapter
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
        return adapters.keys.sorted().joinToString("\n") { "  $it: connected" }
    }
}
