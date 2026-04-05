/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.integration

import java.time.Instant
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.support.MessageBuilder
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

/** Publishes a 1-second heartbeat to the tick channel for all TickListener subscribers */
@Service
@ConditionalOnProperty("streampack.tick.scheduler.enabled", matchIfMissing = true)
class TickService(@Qualifier("tickChannel") private val tickChannel: MessageChannel) {

    @Scheduled(fixedRate = 1000)
    fun tick() {
        tickChannel.send(MessageBuilder.withPayload(Instant.now()).build())
    }
}
