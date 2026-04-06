/* Joseph B. Ottinger (C)2026 */
package dev.streampack.sentiment.operation

import dev.streampack.ai.config.AiProperties
import dev.streampack.ai.service.AiService
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.service.MessageLogService
import dev.streampack.sentiment.model.SentimentRequest
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.ai.chat.model.ChatModel
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.messaging.support.MessageBuilder

@SpringBootTest
class SentimentOperationTests {

    @TestConfiguration
    class MockAiConfig {

        /** Provides a mock AiService that returns predictable sentiment analysis */
        @Bean
        @Primary
        fun aiService(): AiService {
            val mockChatModel = Mockito.mock(ChatModel::class.java)
            val mockProperties = AiProperties(enabled = true)
            return object : AiService(mockChatModel, mockProperties) {
                override fun prompt(systemInstruction: String, userPrompt: String): String {
                    return "Score: 3/10. Generally positive conversation with helpful exchanges."
                }
            }
        }
    }

    @Autowired lateinit var eventGateway: EventGateway

    @Autowired lateinit var sentimentOperation: SentimentOperation

    @Autowired lateinit var messageLogService: MessageLogService

    private val testChannel = "irc://testnet/%23java"

    private fun adminPrincipal() =
        UserPrincipal(
            id = UUID.randomUUID(),
            username = "admin",
            displayName = "Admin User",
            role = Role.ADMIN,
        )

    private fun userPrincipal() =
        UserPrincipal(
            id = UUID.randomUUID(),
            username = "regular",
            displayName = "Regular User",
            role = Role.USER,
        )

    private fun provenance(user: UserPrincipal? = adminPrincipal()) =
        Provenance(protocol = Protocol.IRC, serviceId = "testnet", user = user, replyTo = "#java")

    private fun message(text: String, user: UserPrincipal? = adminPrincipal()) =
        MessageBuilder.withPayload(text)
            .setHeader(Provenance.HEADER, provenance(user))
            .setHeader(Provenance.ADDRESSED, true)
            .setHeader(Provenance.BOT_NICK, "nevet")
            .setHeader("nick", "adminnick")
            .build()

    @BeforeEach
    fun seedLogs() {
        messageLogService.logInbound(testChannel, "alice", "I love this channel")
        messageLogService.logInbound(testChannel, "bob", "Great discussion today")
        messageLogService.logOutbound(testChannel, "nevet", "Here is a factoid for you")
        messageLogService.logInbound(testChannel, "carol", "Thanks, very helpful")
    }

    @Test
    fun `sentiment command analyzes channel logs`() {
        val result = sentimentOperation.execute(message("sentiment #java"))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val success = result as OperationResult.Success
        assertTrue(success.payload.toString().contains("Score:"))
    }

    @Test
    fun `sentiment command is case insensitive`() {
        val result = sentimentOperation.execute(message("Sentiment #java"))

        assertInstanceOf(OperationResult.Success::class.java, result)
    }

    @Test
    fun `triggered sentiment command is accepted`() {
        val result = sentimentOperation.execute(message("!sentiment #java"))

        assertInstanceOf(OperationResult.Success::class.java, result)
    }

    @Test
    fun `bare sentiment command is not handled`() {
        val result = sentimentOperation.execute(message("sentiment"))
        assertNull(result, "Bare 'sentiment' should not be handled")
    }

    @Test
    fun `sentiment with only whitespace after command is not handled`() {
        val result = sentimentOperation.execute(message("sentiment   "))
        assertNull(result, "Sentiment with blank target should not be handled")
    }

    @Test
    fun `non-admin user is rejected`() {
        val result = sentimentOperation.execute(message("sentiment #java", userPrincipal()))

        assertInstanceOf(OperationResult.Error::class.java, result)
        val error = result as OperationResult.Error
        assertTrue(error.message.contains("ADMIN"))
    }

    @Test
    fun `guest user is rejected`() {
        val result = sentimentOperation.execute(message("sentiment #java", null))

        assertInstanceOf(OperationResult.Error::class.java, result)
    }

    @Test
    fun `sentiment for empty channel returns error`() {
        val result = sentimentOperation.execute(message("sentiment #empty-channel-nobody-uses"))

        assertInstanceOf(OperationResult.Error::class.java, result)
        val error = result as OperationResult.Error
        assertTrue(error.message.contains("No recent messages"))
    }

    @Test
    fun `sentiment for full URI target works`() {
        val fullUri = testChannel
        messageLogService.logInbound(fullUri, "dave", "hello from a full URI test")

        val result = sentimentOperation.execute(message("sentiment $fullUri"))

        assertInstanceOf(OperationResult.Success::class.java, result)
    }

    @Test
    fun `cross-channel sentiment uses provenance override for DM`() {
        val otherChannel = "irc://testnet/%23other"
        messageLogService.logInbound(otherChannel, "eve", "hello from other channel")

        val result = sentimentOperation.execute(message("sentiment #other"))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val success = result as OperationResult.Success
        assertNotNull(success.provenance, "Cross-channel result should have provenance override")
        assertTrue(
            success.provenance!!.replyTo == "adminnick",
            "Cross-channel result should be directed to the requesting user",
        )
    }

    @Test
    fun `same-channel sentiment does not use provenance override`() {
        val result = sentimentOperation.execute(message("sentiment #java"))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val success = result as OperationResult.Success
        assertNull(success.provenance, "Same-channel result should not override provenance")
    }

    @Test
    fun `typed SentimentRequest bypasses translation`() {
        val typedMessage =
            MessageBuilder.withPayload(SentimentRequest(testChannel) as Any)
                .setHeader(Provenance.HEADER, provenance())
                .setHeader(Provenance.ADDRESSED, true)
                .setHeader(Provenance.BOT_NICK, "nevet")
                .build()

        val result = sentimentOperation.execute(typedMessage)

        assertInstanceOf(OperationResult.Success::class.java, result)
    }

    @Test
    fun `non-sentiment message is not handled`() {
        val result = sentimentOperation.execute(message("weather tallahassee"))
        assertNull(result, "Non-sentiment message should not be handled")
    }
}
