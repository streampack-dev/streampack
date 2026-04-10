/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.service.FactoidUpdateBuffer
import dev.streampack.core.integration.EgressSubscriber
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.factoid.model.FactoidUpdatedEvent
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

@SpringBootTest(properties = ["streampack.tick.scheduler.enabled=false"])
@Transactional
class FactoidUpdatedAccumulatorOperationTests {

    class TestSubscriber : EgressSubscriber() {
        val received = CopyOnWriteArrayList<Pair<OperationResult, Provenance>>()

        override fun matches(provenance: Provenance): Boolean =
            provenance.protocol == Protocol.CONSOLE

        override fun deliver(result: OperationResult, provenance: Provenance) {
            received.add(result to provenance)
        }

        fun reset() {
            received.clear()
        }
    }

    @TestConfiguration
    class TestConfig {
        @Bean fun testSubscriber() = TestSubscriber()
    }

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var factoidUpdateBuffer: FactoidUpdateBuffer
    @Autowired lateinit var testSubscriber: TestSubscriber

    @BeforeEach
    fun setUp() {
        factoidUpdateBuffer.drain()
        testSubscriber.reset()
    }

    @Test
    fun `factoid updated event accumulates selector for deferred rerender without visible output`() {
        val result =
            eventGateway.process(
                MessageBuilder.withPayload(FactoidUpdatedEvent("thing"))
                    .setHeader(
                        Provenance.HEADER,
                        Provenance(protocol = Protocol.CONSOLE, serviceId = "", replyTo = "local"),
                    )
                    .build()
            )

        assertEquals(OperationResult.NotHandled, result)
        assertEquals(setOf("thing"), factoidUpdateBuffer.drain())
        assertEquals(0, testSubscriber.received.size)
    }
}
