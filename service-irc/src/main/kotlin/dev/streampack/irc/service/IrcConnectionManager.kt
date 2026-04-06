/* Joseph B. Ottinger (C)2026 */
package dev.streampack.irc.service

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.ChannelControlService
import dev.streampack.core.service.ProtocolAdapter
import dev.streampack.core.service.ProvenanceStateService
import dev.streampack.core.service.SecretRefEnvironment
import dev.streampack.core.service.UserResolutionService
import dev.streampack.irc.config.IrcProperties
import dev.streampack.irc.entity.IrcNetwork
import dev.streampack.irc.repository.IrcChannelRepository
import dev.streampack.irc.repository.IrcNetworkRepository
import java.util.concurrent.ConcurrentHashMap
import org.kitteh.irc.client.library.Client
import org.kitteh.irc.client.library.feature.auth.SaslPlain
import org.kitteh.irc.client.library.feature.sending.SingleDelaySender
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * Manages active IRC connections. Only instantiated when streampack.irc.enabled=true. Reads
 * autoconnect networks on startup and maintains a live adapter per connected network.
 */
@Component
@ConditionalOnProperty("streampack.irc.enabled", havingValue = "true")
class IrcConnectionManager(
    @Suppress("unused") private val secretRefStartupGuard: IrcSecretRefStartupGuard,
    private val eventGateway: EventGateway,
    private val userResolutionService: UserResolutionService,
    private val channelControlService: ChannelControlService,
    private val provenanceStateService: ProvenanceStateService,
    private val springEnvironment: Environment,
    private val ircProperties: IrcProperties,
    private val networkRepository: IrcNetworkRepository,
    private val channelRepository: IrcChannelRepository,
) : InitializingBean, DisposableBean, ProtocolAdapter {
    override val protocol: Protocol = Protocol.IRC
    override val serviceName: String = "irc"

    override fun wouldTriggerIngress(text: String): Boolean = false

    override fun sendReply(provenance: Provenance, text: String) {
        // Individual per-network IrcAdapters handle message delivery
    }

    private val logger = LoggerFactory.getLogger(IrcConnectionManager::class.java)
    private val adapters = ConcurrentHashMap<String, IrcAdapter>()

    override fun afterPropertiesSet() {
        val autoconnectNetworks = networkRepository.findByAutoconnectTrueAndDeletedFalse()
        for (network in autoconnectNetworks) {
            logger.info("Auto-connecting to network '{}'", network.name)
            connect(network)
        }
    }

    override fun destroy() {
        logger.info("Shutting down all IRC connections")
        for ((name, adapter) in adapters) {
            logger.info("Disconnecting from '{}'", name)
            adapter.disconnect()
        }
        adapters.clear()
    }

    /** Builds a Kitteh Client and connects to the given network */
    fun connect(network: IrcNetwork) {
        if (adapters.containsKey(network.name)) {
            logger.warn("Already connected to network '{}'", network.name)
            return
        }

        val securityType =
            if (network.tls) Client.Builder.Server.SecurityType.SECURE
            else Client.Builder.Server.SecurityType.INSECURE

        val client =
            Client.builder()
                .nick(network.nick)
                .user(network.nick)
                .realName(ircProperties.identity)
                .server()
                .host(network.host)
                .port(network.port, securityType)
                .then()
                .build()
        if (ircProperties.adaptiveSendDelayEnabled) {
            val minDelayMs = ircProperties.minSendDelayMs.coerceAtLeast(120)
            val maxDelayMs = ircProperties.maxSendDelayMs.coerceAtLeast(minDelayMs)
            val rampUp = ircProperties.sendDelayRampUpFactor.coerceAtLeast(1.0)
            val rampDown =
                ircProperties.sendDelayRampDownFactor.coerceAtMost(1.0).coerceAtLeast(0.1)
            client.setMessageSendingQueueSupplier(
                AdaptiveDelaySender.getSupplier(minDelayMs, maxDelayMs, rampUp, rampDown)
            )
            logger.info(
                "Configured adaptive IRC send delay for '{}' (min={}ms, max={}ms, up={}, down={})",
                network.name,
                minDelayMs,
                maxDelayMs,
                rampUp,
                rampDown,
            )
        } else {
            val sendDelayMs = ircProperties.sendDelayMs.coerceAtLeast(250)
            client.setMessageSendingQueueSupplier(SingleDelaySender.getSupplier(sendDelayMs))
            logger.info(
                "Configured fixed IRC send delay for '{}' at {}ms",
                network.name,
                sendDelayMs,
            )
        }

        val account =
            network.saslAccount?.let { secret ->
                SecretRefEnvironment.resolve(secret) { key ->
                    System.getenv(key) ?: springEnvironment.getProperty(key)
                }
            }
        val password =
            network.saslPassword?.let { secret ->
                SecretRefEnvironment.resolve(secret) { key ->
                    System.getenv(key) ?: springEnvironment.getProperty(key)
                }
            }
        if (account != null && password != null) {
            client.authManager.addProtocol(SaslPlain(client, account, password))
        }

        val effectiveSignal = network.signalCharacter ?: ircProperties.signalCharacter
        val adapter =
            IrcAdapter(
                networkName = network.name,
                eventGateway = eventGateway,
                userResolutionService = userResolutionService,
                channelControlService = channelControlService,
                stateService = provenanceStateService,
                networkRepository = networkRepository,
                channelRepository = channelRepository,
                client = client,
                signalCharacter = effectiveSignal,
                identity = ircProperties.identity,
            )
        adapters[network.name] = adapter
        adapter.connect()
    }

    fun disconnect(networkName: String) {
        val adapter = adapters.remove(networkName)
        if (adapter != null) {
            adapter.disconnect()
            logger.info("Disconnected from '{}'", networkName)
        }
    }

    fun join(networkName: String, channelName: String) {
        adapters[networkName]?.joinChannel(channelName)
    }

    fun leave(networkName: String, channelName: String) {
        adapters[networkName]?.leaveChannel(channelName)
    }

    /** Returns status summary for a specific network or all networks */
    fun getStatus(networkName: String?): String {
        if (networkName != null) {
            val adapter = adapters[networkName]
            return if (adapter != null) {
                val channels = adapter.getJoinedChannels()
                "Network '$networkName': connected, channels=${channels.joinToString(", ")}"
            } else {
                "Network '$networkName': not connected"
            }
        }

        if (adapters.isEmpty()) {
            return "No active IRC connections"
        }

        return adapters.entries.joinToString("\n") { (name, adapter) ->
            val channels = adapter.getJoinedChannels()
            "  $name: ${channels.size} channel(s) [${channels.joinToString(", ")}]"
        }
    }

    /** Returns the adapter for the given network, or null if not connected */
    fun getAdapter(networkName: String): IrcAdapter? = adapters[networkName]

    fun isConnected(networkName: String): Boolean = adapters.containsKey(networkName)
}
