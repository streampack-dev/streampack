/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mail.service

import com.icegreen.greenmail.configuration.GreenMailConfiguration
import com.icegreen.greenmail.junit5.GreenMailExtension
import com.icegreen.greenmail.util.ServerSetupTest
import dev.streampack.core.integration.EgressSubscriber
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.Operation
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.support.MessageBuilder
import org.springframework.stereotype.Component

/** Tests the mail egress subscriber using GreenMail to capture sent emails */
@SpringBootTest(properties = ["streampack.mail.enabled=true"])
class MailEgressSubscriberTests {

    companion object {
        @JvmField
        @RegisterExtension
        val greenMail: GreenMailExtension =
            GreenMailExtension(ServerSetupTest.SMTP)
                .withConfiguration(GreenMailConfiguration.aConfig().withDisabledAuthentication())
    }

    @TestConfiguration
    class Config {

        /** Echo operation that returns the text after "echo " as a success */
        @Bean
        fun testEchoOp() =
            object : Operation {
                override val priority = 10

                override fun canHandle(message: Message<*>): Boolean =
                    (message.payload as? String)?.startsWith("echo ") == true

                override fun execute(message: Message<*>): OperationOutcome =
                    OperationResult.Success(
                        (message.payload as String).removePrefix("echo ").trim()
                    )
            }

        /** Fail operation that returns an error with the text after "fail " */
        @Bean
        fun testFailOp() =
            object : Operation {
                override val priority = 10

                override fun canHandle(message: Message<*>): Boolean =
                    (message.payload as? String)?.startsWith("fail ") == true

                override fun execute(message: Message<*>): OperationOutcome =
                    OperationResult.Error((message.payload as String).removePrefix("fail ").trim())
            }
    }

    /** Captures egress messages for non-MAILTO protocols so we can verify filtering */
    @Component
    class CapturingConsoleSubscriber : EgressSubscriber() {
        val received = CopyOnWriteArrayList<OperationResult>()

        override fun matches(provenance: Provenance): Boolean =
            provenance.protocol == Protocol.CONSOLE

        override fun deliver(result: OperationResult, provenance: Provenance) {
            received.add(result)
        }

        fun reset() {
            received.clear()
        }
    }

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired @Qualifier("egressChannel") lateinit var egressChannel: MessageChannel

    @Autowired lateinit var consoleSubscriber: CapturingConsoleSubscriber

    private fun mailMessage(text: String) =
        MessageBuilder.withPayload(text)
            .setHeader(
                Provenance.HEADER,
                Provenance(protocol = Protocol.MAILTO, serviceId = "", replyTo = "user@example.com"),
            )
            .build()

    private fun consoleMessage(text: String) =
        MessageBuilder.withPayload(text)
            .setHeader(
                Provenance.HEADER,
                Provenance(protocol = Protocol.CONSOLE, serviceId = "", replyTo = "local"),
            )
            .build()

    @BeforeEach
    fun setUp() {
        greenMail.reset()
        consoleSubscriber.reset()
    }

    @Test
    fun `Success result sends email to correct address`() {
        eventGateway.process(mailMessage("echo hello from nevet"))

        val messages = greenMail.receivedMessages
        assertEquals(1, messages.size)
        assertEquals("Nevet notification", messages[0].subject)
        assertEquals("user@example.com", messages[0].allRecipients[0].toString())
        assertTrue(messages[0].content.toString().trim().contains("hello from nevet"))
    }

    @Test
    fun `Error result sends email with error prefix`() {
        eventGateway.process(mailMessage("fail something broke"))

        val messages = greenMail.receivedMessages
        assertEquals(1, messages.size)
        assertEquals("Nevet notification", messages[0].subject)
        assertTrue(messages[0].content.toString().trim().contains("Error: something broke"))
    }

    @Test
    fun `NotHandled result sends no email`() {
        eventGateway.process(mailMessage("unknown command"))

        val messages = greenMail.receivedMessages
        assertEquals(0, messages.size)
    }

    @Test
    fun `non-MAILTO provenance is not matched by mail subscriber`() {
        eventGateway.process(consoleMessage("echo console only"))

        val messages = greenMail.receivedMessages
        assertEquals(0, messages.size)
        // Verify the console subscriber DID get the message
        assertEquals(1, consoleSubscriber.received.size)
    }

    @Test
    fun `subject header overrides default subject`() {
        val message =
            MessageBuilder.withPayload(OperationResult.Success("custom body"))
                .setHeader(
                    Provenance.HEADER,
                    Provenance(
                        protocol = Protocol.MAILTO,
                        serviceId = "",
                        replyTo = "user@example.com",
                    ),
                )
                .setHeader("streampack.mail.subject", "Custom Subject")
                .build()

        egressChannel.send(message)

        val messages = greenMail.receivedMessages
        assertEquals(1, messages.size)
        assertEquals("Custom Subject", messages[0].subject)
        assertTrue(messages[0].content.toString().trim().contains("custom body"))
    }
}
