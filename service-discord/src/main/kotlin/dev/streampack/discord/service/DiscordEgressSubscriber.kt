/* Joseph B. Ottinger (C)2026 */
package dev.streampack.discord.service

import dev.streampack.core.integration.EgressSubscriber
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.ChannelControlService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/** Routes operation results back to Discord channels or DMs */
@Component
@ConditionalOnProperty("streampack.discord.enabled", havingValue = "true")
class DiscordEgressSubscriber(
    private val discordAdapter: DiscordAdapter,
    private val channelControlService: ChannelControlService,
) : EgressSubscriber() {

    private val logger = LoggerFactory.getLogger(DiscordEgressSubscriber::class.java)

    override fun matches(provenance: Provenance): Boolean = provenance.protocol == Protocol.DISCORD

    override fun resolveSignalCharacter(provenance: Provenance): String =
        discordAdapter.signalCharacter

    override fun deliver(result: OperationResult, provenance: Provenance) {
        val isMuted =
            channelControlService.getOptions(provenance.identityEncode())?.automute ?: false
        if (isMuted) {
            logger.debug("Channel '{}' is muted, suppressing reply", provenance.replyTo)
            return
        }

        val text =
            when (result) {
                is OperationResult.Success -> result.payload.toString()
                is OperationResult.Error -> "Error: ${result.message}"
                is OperationResult.NotHandled -> return
            }

        if (discordAdapter.wouldTriggerIngress(text)) {
            logger.warn("Suppressing looping output on '{}': {}", provenance.replyTo, text.take(80))
            return
        }
        discordAdapter.sendReply(provenance, text)
    }
}
