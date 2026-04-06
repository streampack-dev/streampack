/* Joseph B. Ottinger (C)2026 */
package dev.streampack.polling.service

import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.TransformerChainService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.support.MessageBuilder
import org.springframework.stereotype.Component

/** Sends notifications to the egress channel with decoded provenance addressing */
@Component
class EgressNotifier(
    @Qualifier("egressChannel") private val egressChannel: MessageChannel,
    private val transformerChain: TransformerChainService,
) {
    private val logger = LoggerFactory.getLogger(EgressNotifier::class.java)

    /** Send a text notification to a destination identified by its provenance URI */
    fun send(text: String, destinationUri: String) {
        try {
            val provenance = Provenance.decode(destinationUri)
            val raw = OperationResult.Success(text)
            val transformed = transformerChain.apply(raw, provenance)
            val message =
                MessageBuilder.withPayload(transformed as Any)
                    .setHeader(Provenance.HEADER, provenance)
                    .build()
            egressChannel.send(message)
        } catch (e: Exception) {
            logger.warn("Failed to send notification to {}: {}", destinationUri, e.message)
        }
    }
}
