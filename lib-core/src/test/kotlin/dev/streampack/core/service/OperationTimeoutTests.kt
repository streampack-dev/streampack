/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import com.enigmastation.streampack.core.TestChannelConfiguration
import com.enigmastation.streampack.core.integration.EventGateway
import com.enigmastation.streampack.core.model.OperationOutcome
import com.enigmastation.streampack.core.model.OperationResult
import com.enigmastation.streampack.core.model.Protocol
import com.enigmastation.streampack.core.model.Provenance
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.messaging.Message
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
@Import(TestChannelConfiguration::class)
class OperationTimeoutTests {

    @TestConfiguration
    class TestOps {

        /** Operation that sleeps longer than its timeout allows */
        @Bean
        fun slowOperation() =
            object : Operation {
                override val priority = 10
                override val timeout: Duration = Duration.ofSeconds(2)

                override fun canHandle(message: Message<*>): Boolean =
                    (message.payload as? String) == "slow"

                override fun execute(message: Message<*>): OperationOutcome {
                    Thread.sleep(5_000)
                    return OperationResult.Success("should not reach this")
                }
            }

        /** Fallback operation at lower priority that proves the chain continues after timeout */
        @Bean
        fun fallbackOperation() =
            object : Operation {
                override val priority = 50

                override fun canHandle(message: Message<*>): Boolean =
                    (message.payload as? String) == "slow"

                override fun execute(message: Message<*>): OperationOutcome =
                    OperationResult.Success("fallback handled it")
            }

        /** Fast operation to verify normal operations are unaffected by timeout machinery */
        @Bean
        fun fastOperation() =
            object : Operation {
                override val priority = 10

                override fun canHandle(message: Message<*>): Boolean =
                    (message.payload as? String) == "fast"

                override fun execute(message: Message<*>): OperationOutcome =
                    OperationResult.Success("fast response")
            }
    }

    @Autowired lateinit var eventGateway: EventGateway

    private fun buildMessage(payload: String): Message<String> {
        val provenance = Provenance(protocol = Protocol.CONSOLE, serviceId = "", replyTo = "local")
        return MessageBuilder.withPayload(payload).setHeader(Provenance.HEADER, provenance).build()
    }

    @Test
    fun `timed out operation is skipped and chain continues to fallback`() {
        val start = System.currentTimeMillis()
        val result = eventGateway.process(buildMessage("slow"))
        val elapsed = System.currentTimeMillis() - start

        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload == "fallback handled it") { "Expected fallback, got: $payload" }
        assertTrue(elapsed < 4_000) { "Should complete in ~2s timeout, took ${elapsed}ms" }
    }

    @Test
    fun `fast operation completes normally`() {
        val result = eventGateway.process(buildMessage("fast"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload == "fast response") { "Expected fast response, got: $payload" }
    }
}
