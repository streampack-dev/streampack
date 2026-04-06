/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import com.icegreen.greenmail.configuration.GreenMailConfiguration
import com.icegreen.greenmail.junit5.GreenMailExtension
import com.icegreen.greenmail.util.ServerSetupTest
import dev.streampack.blog.model.OtpRequest
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.repository.OneTimeCodeRepository
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class OtpRequestOperationTests {

    companion object {
        @JvmField
        @RegisterExtension
        val greenMail: GreenMailExtension =
            GreenMailExtension(ServerSetupTest.SMTP)
                .withConfiguration(GreenMailConfiguration.aConfig().withDisabledAuthentication())
    }

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var oneTimeCodeRepository: OneTimeCodeRepository

    private val provenance =
        Provenance(
            protocol = Protocol.HTTP,
            serviceId = "blog-service",
            replyTo = "auth/otp/request",
        )

    @BeforeEach
    fun setUp() {
        greenMail.reset()
    }

    private fun otpRequestMessage(email: String) =
        MessageBuilder.withPayload(OtpRequest(email))
            .setHeader(Provenance.HEADER, provenance)
            .build()

    @Test
    fun `code is generated and email is sent`() {
        val result = eventGateway.process(otpRequestMessage("user@example.com"))

        assertInstanceOf(OperationResult.Success::class.java, result)

        val codes = oneTimeCodeRepository.findAll().filter { it.email == "user@example.com" }
        assertEquals(1, codes.size)
        assertEquals(6, codes[0].code.length)
        assertTrue(codes[0].expiresAt.isAfter(Instant.now()))

        val messages = greenMail.receivedMessages
        assertEquals(1, messages.size)
        assertTrue(messages[0].subject.contains("sign-in code"))
    }

    @Test
    fun `response never leaks email existence`() {
        val result = eventGateway.process(otpRequestMessage("nobody@example.com"))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("code has been sent"))
    }

    @Test
    fun `rate limit prevents more than max active codes`() {
        repeat(3) { eventGateway.process(otpRequestMessage("limited@example.com")) }

        /* Fourth request should still return success but not create another code */
        val result = eventGateway.process(otpRequestMessage("limited@example.com"))
        assertInstanceOf(OperationResult.Success::class.java, result)

        val codes = oneTimeCodeRepository.findAll().filter { it.email == "limited@example.com" }
        assertEquals(3, codes.size)
    }
}
