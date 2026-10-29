/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.support.MessageBuilder
import org.springframework.stereotype.Service

/** [Egress] onto the egress channel, after the transformer chain. */
@Service
class ChannelEgress(
    @Qualifier("egressChannel") private val egressChannel: MessageChannel,
    private val transformerChain: TransformerChainService,
) : Egress {
    private val logger = LoggerFactory.getLogger(ChannelEgress::class.java)

    override fun send(result: OperationResult, to: Provenance) {
        try {
            val transformed = transformerChain.apply(result, to)
            egressChannel.send(
                MessageBuilder.withPayload(transformed as Any)
                    .setHeader(Provenance.HEADER, to)
                    .build(),
            )
        } catch (e: Exception) {
            logger.warn("Failed to send to {}: {}", to.encode(), e.message)
        }
    }

    override fun send(text: String, destinationUri: String) {
        val provenance =
            try {
                Provenance.decode(destinationUri)
            } catch (e: Exception) {
                logger.warn("Failed to send to {}: {}", destinationUri, e.message)
                return
            }
        send(OperationResult.Success(text), provenance)
    }
}
