/* Joseph B. Ottinger (C)2026 */
package dev.streampack.slack.service

import dev.streampack.core.integration.EgressSubscriber
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.ChannelControlService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Watches the egress channel for Slack-bound results and routes them through the appropriate
 * workspace adapter. Muted channels are silenced here -- the message was still processed by
 * operations, but the reply is suppressed.
 */
@Component
@ConditionalOnProperty("streampack.slack.enabled", havingValue = "true")
class SlackEgressSubscriber(
    private val connectionManager: SlackConnectionManager,
    private val channelControlService: ChannelControlService,
) : EgressSubscriber() {
    private val logger = LoggerFactory.getLogger(SlackEgressSubscriber::class.java)

    override fun matches(provenance: Provenance): Boolean = provenance.protocol == Protocol.SLACK

    override fun resolveSignalCharacter(provenance: Provenance): String {
        val workspaceName = provenance.serviceId ?: return ""
        return connectionManager.getAdapter(workspaceName)?.signalCharacter ?: ""
    }

    override fun deliver(result: OperationResult, provenance: Provenance) {
        val workspaceName = provenance.serviceId
        if (workspaceName == null) {
            logger.warn("Slack egress message has no serviceId, dropping")
            return
        }
        val adapter = connectionManager.getAdapter(workspaceName)
        if (adapter == null) {
            logger.warn("No adapter for workspace '{}', dropping egress message", workspaceName)
            return
        }

        val isMuted = channelControlService.getOptions(provenance.encode())?.automute ?: false
        if (isMuted) {
            logger.trace(
                "Channel '{}' is muted on '{}', suppressing reply",
                provenance.replyTo,
                workspaceName,
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
