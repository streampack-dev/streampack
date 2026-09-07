/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mattermost.service

import dev.streampack.core.integration.EgressSubscriber
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.ChannelControlService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/** Routes operation results back to Mattermost channels or direct messages. */
@Component
@ConditionalOnProperty("streampack.mattermost.enabled", havingValue = "true")
class MattermostEgressSubscriber(
    private val connectionManager: MattermostConnectionManager,
    private val channelControlService: ChannelControlService,
) : EgressSubscriber() {
    private val logger = LoggerFactory.getLogger(MattermostEgressSubscriber::class.java)

    override fun matches(provenance: Provenance): Boolean =
        provenance.protocol == Protocol.MATTERMOST

    override fun resolveSignalCharacter(provenance: Provenance): String {
        val serverName = provenance.serviceId ?: return ""
        return connectionManager.getAdapter(serverName)?.signalCharacter ?: ""
    }

    override fun deliver(result: OperationResult, provenance: Provenance) {
        val serverName = provenance.serviceId
        if (serverName == null) {
            logger.warn("Mattermost egress message has no serviceId, dropping")
            return
        }
        val adapter = connectionManager.getAdapter(serverName)
        if (adapter == null) {
            logger.warn(
                "No adapter for Mattermost server '{}', dropping egress message",
                serverName,
            )
            return
        }

        val isMuted = channelControlService.getOptions(provenance.encode())?.automute ?: false
        if (isMuted) return

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
