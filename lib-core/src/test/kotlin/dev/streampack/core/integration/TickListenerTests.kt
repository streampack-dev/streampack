/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.integration

import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.support.MessageBuilder

@SpringBootTest
class TickListenerTests {

    @TestConfiguration
    class TickTestConfig {

        @Bean fun firstTickListener(): CapturingTickListener = CapturingTickListener()

        @Bean fun secondTickListener(): CapturingTickListener = CapturingTickListener()
    }

    /** Captures tick instants for assertion */
    class CapturingTickListener : TickListener {
        val received = CopyOnWriteArrayList<Instant>()

        override fun onTick(now: Instant) {
            received.add(now)
        }

        fun clear() = received.clear()
    }

    @Autowired @Qualifier("tickChannel") lateinit var tickChannel: MessageChannel

    @Autowired lateinit var firstTickListener: CapturingTickListener

    @Autowired lateinit var secondTickListener: CapturingTickListener

    @BeforeEach
    fun setUp() {
        firstTickListener.clear()
        secondTickListener.clear()
    }

    @Test
    fun `tick listener receives manually published tick`() {
        val now = Instant.now()
        tickChannel.send(MessageBuilder.withPayload(now).build())

        assertEquals(1, firstTickListener.received.size)
        assertEquals(now, firstTickListener.received[0])
    }

    @Test
    fun `multiple listeners all receive the same tick`() {
        val now = Instant.now()
        tickChannel.send(MessageBuilder.withPayload(now).build())

        assertEquals(1, firstTickListener.received.size)
        assertEquals(1, secondTickListener.received.size)
        assertEquals(now, firstTickListener.received[0])
        assertEquals(now, secondTickListener.received[0])
    }

    @Test
    fun `multiple ticks are delivered in order`() {
        val first = Instant.now()
        val second = first.plusSeconds(1)
        val third = first.plusSeconds(2)

        tickChannel.send(MessageBuilder.withPayload(first).build())
        tickChannel.send(MessageBuilder.withPayload(second).build())
        tickChannel.send(MessageBuilder.withPayload(third).build())

        assertEquals(3, firstTickListener.received.size)
        assertTrue(firstTickListener.received[0].isBefore(firstTickListener.received[1]))
        assertTrue(firstTickListener.received[1].isBefore(firstTickListener.received[2]))
    }

    @Test
    fun `non-Instant payload is ignored`() {
        tickChannel.send(MessageBuilder.withPayload("not an instant").build())

        assertEquals(0, firstTickListener.received.size)
        assertEquals(0, secondTickListener.received.size)
    }
}
