/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.integration

import jakarta.annotation.PostConstruct
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Configuration
import org.springframework.messaging.SubscribableChannel

/**
 * Discovers all [TickListener] beans and subscribes them to the tick channel. Each listener
 * receives every tick and decides internally whether to act on it.
 */
@Configuration
class TickListenerWiring(
    @Qualifier("tickChannel") private val tickChannel: SubscribableChannel,
    private val listeners: List<TickListener>,
) {

    private val logger = LoggerFactory.getLogger(TickListenerWiring::class.java)

    @PostConstruct
    fun wireListeners() {
        listeners.forEach { listener ->
            tickChannel.subscribe { message ->
                val now = message.payload as? Instant ?: return@subscribe
                listener.onTick(now)
            }
            logger.debug("Registered tick listener: {}", listener::class.simpleName)
        }
        if (listeners.isNotEmpty()) {
            logger.info("Registered {} tick listener(s)", listeners.size)
        }
    }
}
