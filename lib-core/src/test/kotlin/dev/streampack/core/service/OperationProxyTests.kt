/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.TestChannelConfiguration
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.messaging.Message
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Import(
    TestChannelConfiguration::class,
    OperationProxyTests.TransactionalTypedOperation::class,
    OperationProxyTests.TransactionalTranslatingOperation::class,
)
class OperationProxyTests {

    @Autowired lateinit var eventGateway: EventGateway

    @Test
    fun `transactional typed operation preserves payload type under proxy`() {
        val result = eventGateway.process(message(TransactionalTypedRequest("ok")))

        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals("typed:ok", (result as OperationResult.Success).payload)
    }

    @Test
    fun `transactional translating operation preserves payload type under proxy`() {
        val result = eventGateway.process(message("translate:ok"))

        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals("translated:ok", (result as OperationResult.Success).payload)
    }

    private fun message(payload: Any): Message<Any> =
        MessageBuilder.withPayload(payload)
            .setHeader(
                Provenance.HEADER,
                Provenance(protocol = Protocol.CONSOLE, serviceId = "", replyTo = "local"),
            )
            .build()

    data class TransactionalTypedRequest(val value: String)

    data class TransactionalTranslatingRequest(val value: String)

    @Transactional(readOnly = true)
    class TransactionalTypedOperation :
        TypedOperation<TransactionalTypedRequest>(TransactionalTypedRequest::class) {
        override val priority = 1

        override fun handle(
            payload: TransactionalTypedRequest,
            message: Message<*>,
        ): OperationOutcome = OperationResult.Success("typed:${payload.value}")
    }

    @Transactional(readOnly = true)
    class TransactionalTranslatingOperation :
        TranslatingOperation<TransactionalTranslatingRequest>(
            TransactionalTranslatingRequest::class
        ) {
        override val priority = 1

        override fun translate(
            payload: String,
            message: Message<*>,
        ): TransactionalTranslatingRequest? =
            payload
                .removePrefix("translate:")
                .takeIf { it != payload }
                ?.let { TransactionalTranslatingRequest(it) }

        override fun handle(
            payload: TransactionalTranslatingRequest,
            message: Message<*>,
        ): OperationOutcome = OperationResult.Success("translated:${payload.value}")
    }
}
