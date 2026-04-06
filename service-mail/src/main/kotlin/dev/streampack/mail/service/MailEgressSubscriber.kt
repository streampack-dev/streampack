/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mail.service

import dev.streampack.core.config.StreampackProperties
import dev.streampack.core.integration.EgressSubscriber
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.MailHeaders
import dev.streampack.core.service.ProtocolAdapter
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.messaging.MessageHeaders
import org.springframework.stereotype.Component

/** Watches the egress channel for mailto-provenance results and delivers them as email */
@Component
@ConditionalOnProperty("streampack.mail.enabled", havingValue = "true")
class MailEgressSubscriber(
    private val mailSender: JavaMailSender,
    private val properties: StreampackProperties,
) : EgressSubscriber(), ProtocolAdapter {
    override val protocol: Protocol = Protocol.MAILTO
    override val serviceName: String = "mail"

    override fun wouldTriggerIngress(text: String): Boolean = false

    override fun sendReply(provenance: Provenance, text: String) {
        // Egress subscriber handles email delivery via deliver()
    }

    private val logger = LoggerFactory.getLogger(MailEgressSubscriber::class.java)

    override fun matches(provenance: Provenance): Boolean = provenance.protocol == Protocol.MAILTO

    override fun deliver(result: OperationResult, provenance: Provenance) {
        deliver(result, provenance, MessageHeaders(emptyMap()))
    }

    override fun deliver(result: OperationResult, provenance: Provenance, headers: MessageHeaders) {
        val text =
            when (result) {
                is OperationResult.Success -> result.payload.toString()
                is OperationResult.Error -> "Error: ${result.message}"
                is OperationResult.NotHandled -> return
            }

        val to = provenance.replyTo
        val message = SimpleMailMessage()
        message.from = properties.mail.from
        message.setTo(to)
        message.subject = headers[MailHeaders.SUBJECT] as? String ?: "Nevet notification"
        message.text = text

        logger.info("Sending notification email to {}", to)
        mailSender.send(message)
    }
}
