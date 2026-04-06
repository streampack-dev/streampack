/* Joseph B. Ottinger (C)2026 */
package dev.streampack.polling.service

import dev.streampack.core.integration.EgressSubscriber
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.messaging.SubscribableChannel

@SpringBootTest
class EgressNotifierTests {

    @TestConfiguration
    class CapturingConfig {
        @Bean
        fun capturingSubscriber(
            @Qualifier("egressChannel") egressChannel: SubscribableChannel
        ): CapturingSubscriber {
            val subscriber = CapturingSubscriber()
            egressChannel.subscribe(subscriber)
            return subscriber
        }
    }

    class CapturingSubscriber : EgressSubscriber() {
        val captured = CopyOnWriteArrayList<Pair<OperationResult, Provenance>>()

        override fun matches(provenance: Provenance): Boolean = true

        override fun deliver(result: OperationResult, provenance: Provenance) {
            captured.add(result to provenance)
        }
    }

    @Autowired lateinit var egressNotifier: EgressNotifier
    @Autowired lateinit var capturingSubscriber: CapturingSubscriber

    @Test
    fun `send delivers message with decoded provenance`() {
        val uri = "irc://libera/%23java"
        egressNotifier.send("Hello from test", uri)

        assertEquals(1, capturingSubscriber.captured.size)
        val (result, provenance) = capturingSubscriber.captured[0]
        assertEquals(Protocol.IRC, provenance.protocol)
        assertEquals("libera", provenance.serviceId)
        assertTrue(result is OperationResult.Success)
        assertEquals("Hello from test", (result as OperationResult.Success).payload)
    }

    @Test
    fun `send with invalid URI logs warning without throwing`() {
        // Should not throw - the error is logged
        egressNotifier.send("Hello", "not-a-valid-uri")
        // No assertion needed beyond not throwing
    }
}
