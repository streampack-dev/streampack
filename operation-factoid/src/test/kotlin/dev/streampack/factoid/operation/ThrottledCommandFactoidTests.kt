/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.operation

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.ThrottlePolicy
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.service.Operation
import dev.streampack.core.service.OperationService
import dev.streampack.core.service.ThrottleService
import dev.streampack.factoid.repository.FactoidRepository
import dev.streampack.test.ResetDatabaseBeforeEach
import java.time.Duration
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.messaging.Message
import org.springframework.messaging.support.MessageBuilder

/**
 * #116 against the real factoid setter: a throttled command that reads like an assignment ("ask why
 * is foo") must not become the factoid "ask why". The stand-in has Ask's place in the chain
 * (priority 50, ahead of the setter's 75; addressed; throttled), since this module doesn't depend
 * on operation-ask.
 */
@SpringBootTest
@ResetDatabaseBeforeEach
class ThrottledCommandFactoidTests {

    @TestConfiguration
    class AskLike {
        /** Answers "ask ...", once an hour per provenance, as Ask does five times. */
        @Bean
        fun askLikeOperation() =
            object : Operation {
                override val priority = 50
                override val addressed = true
                override val throttlePolicy = ThrottlePolicy(1, Duration.ofHours(1))

                override fun canHandle(message: Message<*>): Boolean =
                    (message.payload as? String)?.startsWith("ask ") == true

                override fun execute(message: Message<*>): OperationOutcome =
                    OperationResult.Success("answered")
            }
    }

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var factoidRepository: FactoidRepository
    @Autowired lateinit var throttleService: ThrottleService

    @BeforeEach
    fun freshBuckets() {
        throttleService.clear()
    }

    private fun message(text: String) =
        MessageBuilder.withPayload(text)
            .setHeader(
                Provenance.HEADER,
                Provenance(
                    protocol = Protocol.CONSOLE,
                    serviceId = "",
                    replyTo = "local",
                    user =
                        UserPrincipal(
                            id = UUID.randomUUID(),
                            username = "testuser",
                            displayName = "Test User",
                            role = Role.USER,
                        ),
                ),
            )
            .setHeader("nick", "testuser")
            .build()

    @Test
    fun `a throttled question is refused, not taken as a factoid`() {
        assertEquals(
            OperationResult.Success("answered"),
            eventGateway.process(message("ask why is foo")),
        )

        val throttled = eventGateway.process(message("ask why is foo"))

        assertInstanceOf(OperationResult.Error::class.java, throttled) {
            "answered with $throttled"
        }
        assertEquals(OperationService.THROTTLED, (throttled as OperationResult.Error).message)
        assertNull(factoidRepository.findBySelectorIgnoreCase("ask why"))
    }

    @Test
    fun `an assignment nothing earlier recognizes still sets the factoid`() {
        eventGateway.process(message("ask why is foo"))
        eventGateway.process(message("ask why is foo"))

        val result = eventGateway.process(message("spring is a framework"))

        assertInstanceOf(OperationResult.Success::class.java, result)
        assertNotNull(factoidRepository.findBySelectorIgnoreCase("spring"))
    }
}
