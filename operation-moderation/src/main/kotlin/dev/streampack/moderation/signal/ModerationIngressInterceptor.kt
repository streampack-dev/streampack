/* Joseph B. Ottinger (C)2026 */
package dev.streampack.moderation.signal

import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.ChannelControlService
import dev.streampack.core.service.DirectConversations
import dev.streampack.moderation.config.ModerationProperties
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Configuration
import org.springframework.integration.channel.AbstractMessageChannel
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.stereotype.Component

/**
 * Scores every message said in a public channel as it reaches the ingress channel (#150), beside
 * the logging interceptor, so unaddressed chatter counts as well as commands. It never changes or
 * stops a message, and a failure here never disrupts processing.
 *
 * Direct conversations are never scored: what someone says to the bot directly isn't said in a
 * channel, and isn't anyone's to review. Neither is a channel that isn't logged or has opted out
 * with `moderated=false`.
 */
@Component
class ModerationIngressInterceptor(
    private val scores: ModerationScores,
    private val properties: ModerationProperties,
    private val channelControlService: ChannelControlService,
    private val directConversations: DirectConversations,
) : ChannelInterceptor {
    private val logger = LoggerFactory.getLogger(ModerationIngressInterceptor::class.java)

    override fun preSend(message: Message<*>, channel: MessageChannel): Message<*> {
        if (!properties.enabled) return message
        try {
            val provenance = message.headers[Provenance.HEADER] as? Provenance ?: return message
            if (provenance.protocol !in CHANNEL_PROTOCOLS || provenance.isLoopback()) return message
            if (directConversations.isDirect(provenance)) return message
            if (!channelControlService.isModerated(provenance)) return message
            // The sender as the message log names them, so a review finds their lines
            val sender =
                message.headers["nick"] as? String
                    ?: provenance.user?.displayName
                    ?: provenance.user?.username
                    ?: return message
            val scored =
                scores.record(
                    ModerationScores.Speaker(
                        provenanceUri = provenance.encode(),
                        sender = sender,
                        protocol = provenance.protocol.name.lowercase(),
                        serviceId = provenance.serviceId,
                        userId = provenance.user?.id,
                    ),
                    message.payload.toString(),
                )
            if (scored.signals.isNotEmpty()) {
                logger.debug(
                    "Moderation signals {} in {}",
                    scored.signals.keys,
                    provenance.encode(),
                )
            }
        } catch (e: Exception) {
            logger.warn("Moderation scoring failed: {}", e.message)
        }
        return message
    }

    companion object {
        /** The protocols whose channels the log browser shows: the public conversations. */
        val CHANNEL_PROTOCOLS =
            setOf(Protocol.IRC, Protocol.DISCORD, Protocol.SLACK, Protocol.MATTERMOST)
    }
}

/** Adds [ModerationIngressInterceptor] to the ingress channel. */
@Configuration
class ModerationIngressWiring(
    @Qualifier("ingressChannel") private val ingressChannel: AbstractMessageChannel,
    private val interceptor: ModerationIngressInterceptor,
) {
    @PostConstruct
    fun wireInterceptor() {
        ingressChannel.addInterceptor(interceptor)
    }
}
