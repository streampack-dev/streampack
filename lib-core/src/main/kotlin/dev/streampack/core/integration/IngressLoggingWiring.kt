/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.integration

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Configuration
import org.springframework.integration.channel.AbstractMessageChannel

/** Adds the logging interceptor to the ingress channel for unconditional message capture */
@Configuration
class IngressLoggingWiring(
    @Qualifier("ingressChannel") private val ingressChannel: AbstractMessageChannel,
    private val interceptor: IngressLoggingInterceptor,
) {

    private val logger = LoggerFactory.getLogger(IngressLoggingWiring::class.java)

    @PostConstruct
    fun wireInterceptor() {
        ingressChannel.addInterceptor(interceptor)
        logger.info("Registered ingress logging interceptor")
    }
}
