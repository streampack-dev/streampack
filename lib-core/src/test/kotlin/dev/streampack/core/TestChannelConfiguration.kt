/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.integration.channel.DirectChannel

/**
 * Overrides the ingress channel with a DirectChannel so operations run on the test thread. This
 * allows @Transactional test rollback to work correctly -- the production ExecutorChannel
 * dispatches to virtual threads which cannot see uncommitted test data.
 */
@TestConfiguration
class TestChannelConfiguration {

    @Bean
    @Primary
    fun ingressChannel(): DirectChannel {
        return DirectChannel()
    }
}
