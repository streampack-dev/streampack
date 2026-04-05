/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.integration

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Configuration
import org.springframework.messaging.SubscribableChannel

/**
 * Discovers all [EgressSubscriber] beans and subscribes them to the egress channel.
 *
 * Follows the same auto-discovery pattern as OperationService collecting Operation beans. Each
 * subscriber receives every egress message and filters internally via [EgressSubscriber.matches].
 */
@Configuration
class EgressSubscriberWiring(
    @Qualifier("egressChannel") private val egressChannel: SubscribableChannel,
    private val subscribers: List<EgressSubscriber>,
) {

    private val logger = LoggerFactory.getLogger(EgressSubscriberWiring::class.java)

    @PostConstruct
    fun wireSubscribers() {
        subscribers.forEach { subscriber ->
            egressChannel.subscribe(subscriber)
            logger.debug("Registered egress subscriber: {}", subscriber::class.simpleName)
        }
        if (subscribers.isNotEmpty()) {
            logger.info("Registered {} egress subscriber(s)", subscribers.size)
        }
    }
}
