/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import com.enigmastation.streampack.core.model.OperationResult
import com.enigmastation.streampack.core.model.Protocol
import com.enigmastation.streampack.core.model.Provenance
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.support.MessageBuilder
import org.springframework.stereotype.Service

/** Publishes plain-text email notifications onto the egress channel. */
// @lat: [[operations#User-Facing Operations#Blog Notifications#Delivery Semantics]]
@Service
class MailNotificationPublisher(
    @Qualifier("egressChannel") private val egressChannel: MessageChannel
) {
    private val logger = LoggerFactory.getLogger(MailNotificationPublisher::class.java)

    fun publish(to: String, subject: String, body: String) {
        if (to.isBlank()) return

        val message =
            MessageBuilder.withPayload(OperationResult.Success(body))
                .setHeader(
                    Provenance.HEADER,
                    Provenance(protocol = Protocol.MAILTO, replyTo = to.trim()),
                )
                .setHeader(MailHeaders.SUBJECT, subject)
                .build()

        try {
            egressChannel.send(message)
        } catch (e: Exception) {
            logger.warn("Failed to publish mail notification to {}: {}", to, e.message)
        }
    }

    fun publishAll(recipients: Collection<String>, subject: String, body: String) {
        recipients
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { publish(it, subject, body) }
    }
}
