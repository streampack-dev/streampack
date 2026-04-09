/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.service.FactoidUpdateBuffer
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.factoid.model.FactoidUpdatedEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

@SpringBootTest(properties = ["streampack.tick.scheduler.enabled=false"])
@Transactional
class FactoidUpdatedAccumulatorOperationTests {

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var factoidUpdateBuffer: FactoidUpdateBuffer

    @BeforeEach
    fun setUp() {
        factoidUpdateBuffer.drain()
    }

    @Test
    fun `factoid updated event accumulates selector for deferred rerender`() {
        val result =
            eventGateway.process(MessageBuilder.withPayload(FactoidUpdatedEvent("thing")).build())

        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals(setOf("thing"), factoidUpdateBuffer.drain())
    }
}
