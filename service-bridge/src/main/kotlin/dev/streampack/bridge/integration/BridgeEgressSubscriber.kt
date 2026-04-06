/* Joseph B. Ottinger (C)2026 */
package dev.streampack.bridge.integration

import dev.streampack.bridge.service.BridgeService
import dev.streampack.core.integration.EgressSubscriber
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.support.MessageBuilder
import org.springframework.stereotype.Component

/**
 * Copies bot responses from bridged channels to their copy targets. Messages already marked as
 * bridged are skipped to prevent re-copy loops.
 */
@Component
class BridgeEgressSubscriber(
    private val bridgeService: BridgeService,
    @Qualifier("egressChannel") private val egressChannel: MessageChannel,
) : EgressSubscriber() {

    private val logger = LoggerFactory.getLogger(BridgeEgressSubscriber::class.java)

    override fun matches(provenance: Provenance): Boolean {
        if (provenance.metadata.containsKey(Provenance.BRIDGED)) return false
        return bridgeService.hasCopyTargets(provenance.encode())
    }

    override fun deliver(result: OperationResult, provenance: Provenance) {
        val text =
            when (result) {
                is OperationResult.Success -> result.payload.toString()
                is OperationResult.Error -> return
                is OperationResult.NotHandled -> return
            }

        for (targetUri in bridgeService.getCopyTargets(provenance.encode())) {
            val targetProvenance = Provenance.decode(targetUri)
            val bridgedProvenance =
                targetProvenance.copy(
                    metadata = targetProvenance.metadata + (Provenance.BRIDGED to true)
                )
            val egressMessage =
                MessageBuilder.withPayload(OperationResult.Success(text) as Any)
                    .setHeader(Provenance.HEADER, bridgedProvenance)
                    .build()
            try {
                egressChannel.send(egressMessage)
            } catch (e: Exception) {
                logger.warn("Failed to bridge bot response to {}: {}", targetUri, e.message)
            }
        }
    }
}
