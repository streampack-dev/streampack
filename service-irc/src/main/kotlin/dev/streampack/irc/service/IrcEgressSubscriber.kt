/* Joseph B. Ottinger (C)2026 */
package dev.streampack.irc.service

import dev.streampack.core.integration.EgressSubscriber
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.ChannelControlService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Watches the egress channel for IRC-bound results and routes them through the appropriate network
 * adapter. Muted channels are silenced here -- the message was still processed by operations, but
 * the reply is suppressed.
 */
@Component
@ConditionalOnProperty("streampack.irc.enabled", havingValue = "true")
class IrcEgressSubscriber(
    private val connectionManager: IrcConnectionManager,
    private val channelControlService: ChannelControlService,
) : EgressSubscriber() {
    private val logger = LoggerFactory.getLogger(IrcEgressSubscriber::class.java)

    override fun matches(provenance: Provenance): Boolean = provenance.protocol == Protocol.IRC

    override fun resolveSignalCharacter(provenance: Provenance): String {
        val networkName = provenance.serviceId ?: return ""
        return connectionManager.getAdapter(networkName)?.signalCharacter ?: ""
    }

    override fun deliver(result: OperationResult, provenance: Provenance) {
        val networkName = provenance.serviceId
        if (networkName == null) {
            logger.warn("IRC egress message has no serviceId, dropping")
            return
        }
        val adapter = connectionManager.getAdapter(networkName)
        if (adapter == null) {
            logger.warn("No adapter for network '{}', dropping egress message", networkName)
            return
        }

        val isMuted = channelControlService.getOptions(provenance.encode())?.automute ?: false
        if (isMuted) {
            logger.debug(
                "Channel '{}' is muted on '{}', suppressing reply",
                provenance.replyTo,
                networkName,
            )
            return
        }

        val text =
            when (result) {
                is OperationResult.Success -> result.payload.toString()
                is OperationResult.Error -> "Error: ${result.message}"
                is OperationResult.NotHandled -> return
            }

        if (adapter.wouldTriggerIngress(text)) {
            logger.warn("Suppressing looping output on '{}': {}", provenance.replyTo, text.take(80))
            return
        }
        adapter.sendReply(provenance, text)
    }
}
