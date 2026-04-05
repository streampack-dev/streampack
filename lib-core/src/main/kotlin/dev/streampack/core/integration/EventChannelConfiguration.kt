/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.integration

import java.util.concurrent.Executors
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.integration.annotation.IntegrationComponentScan
import org.springframework.integration.channel.ExecutorChannel
import org.springframework.integration.channel.PublishSubscribeChannel

/** Configures the event system channels and enables discovery of SI gateway interfaces */
@Configuration
@IntegrationComponentScan(basePackageClasses = [EventGateway::class])
class EventChannelConfiguration {

    /** Ingress channel backed by virtual threads for scalable operation processing */
    @Bean
    @ConditionalOnMissingBean(name = ["ingressChannel"])
    fun ingressChannel(): ExecutorChannel {
        return ExecutorChannel(Executors.newVirtualThreadPerTaskExecutor())
    }

    /**
     * Egress channel for operation results. Services subscribe to this channel and claim messages
     * matching their provenance. Dispatches synchronously so subscribers run on the calling thread.
     */
    @Bean
    @ConditionalOnMissingBean(name = ["egressChannel"])
    fun egressChannel(): PublishSubscribeChannel {
        return PublishSubscribeChannel()
    }

    /**
     * Tick channel for 1-second heartbeat pulses. TickListener beans subscribe to this channel to
     * implement timed behavior (polling intervals, countdowns, delayed delivery). Payload is an
     * Instant representing when the tick was emitted.
     */
    @Bean
    @ConditionalOnMissingBean(name = ["tickChannel"])
    fun tickChannel(): PublishSubscribeChannel {
        return PublishSubscribeChannel()
    }
}
