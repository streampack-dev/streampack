/* Joseph B. Ottinger (C)2026 */
package dev.streampack.ask.operation

import dev.streampack.ai.config.AiProperties
import dev.streampack.ai.service.AiService
import dev.streampack.ask.model.AskRequest
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.service.MessageLogService
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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
class AskOperationTests {

    @TestConfiguration
    class MockAiConfig {

        /** Provides a mock AiService that returns predictable answers */
        @Bean
        @Primary
        fun aiService(): AiService {
            val mockChatModel = Mockito.mock(ChatModel::class.java)
            val mockProperties = AiProperties(enabled = true)
            return object : AiService(mockChatModel, mockProperties) {
                override fun prompt(systemInstruction: String, userPrompt: String): String {
                    return "JaCoCo excludes inner classes via the excludes configuration element."
                }
            }
        }
    }

    @Autowired lateinit var askOperation: AskOperation

    @Autowired lateinit var messageLogService: MessageLogService

    private val testChannel = "irc://testnet/%23java"

    private fun userPrincipal() =
        UserPrincipal(
            id = UUID.randomUUID(),
            username = "alice",
            displayName = "Alice",
            role = Role.USER,
        )

    private fun provenance(user: UserPrincipal? = userPrincipal()) =
        Provenance(protocol = Protocol.IRC, serviceId = "testnet", user = user, replyTo = "#java")

    private fun message(text: String, user: UserPrincipal? = userPrincipal()) =
        MessageBuilder.withPayload(text)
            .setHeader(Provenance.HEADER, provenance(user))
            .setHeader(Provenance.ADDRESSED, true)
            .setHeader(Provenance.BOT_NICK, "nevet")
            .build()

    @Test
    fun `ask command returns an answer`() {
        val result = askOperation.execute(message("ask how to exclude inner classes from jacoco"))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val success = result as OperationResult.Success
        assertTrue(success.payload.toString().contains("JaCoCo"))
    }

    @Test
    fun `ask command is case insensitive`() {
        val result = askOperation.execute(message("Ask how to use streams"))

        assertInstanceOf(OperationResult.Success::class.java, result)
    }

    @Test
    fun `bare ask command is not handled`() {
        val result = askOperation.execute(message("ask"))
        assertNull(result, "Bare 'ask' should not be handled")
    }

    @Test
    fun `ask with only whitespace after command is not handled`() {
        val result = askOperation.execute(message("ask   "))
        assertNull(result, "Ask with blank question should not be handled")
    }

    @Test
    fun `non-ask message is not handled`() {
        val result = askOperation.execute(message("weather tallahassee"))
        assertNull(result, "Non-ask message should not be handled")
    }

    @Test
    fun `typed AskRequest bypasses translation`() {
        val typedMessage =
            MessageBuilder.withPayload(AskRequest("what is a monad") as Any)
                .setHeader(Provenance.HEADER, provenance())
                .setHeader(Provenance.ADDRESSED, true)
                .setHeader(Provenance.BOT_NICK, "nevet")
                .build()

        val result = askOperation.execute(typedMessage)

        assertInstanceOf(OperationResult.Success::class.java, result)
    }

    @Test
    fun `context includes recent channel messages`() {
        messageLogService.logInbound(testChannel, "bob", "Spring Boot 4 is out")
        messageLogService.logOutbound(testChannel, "nevet", "Here is the release notes link")
        messageLogService.logInbound(testChannel, "carol", "Thanks for sharing")

        val result = askOperation.execute(message("ask what changed in spring boot 4"))

        assertInstanceOf(OperationResult.Success::class.java, result)
    }

    @Test
    fun `works without provenance`() {
        val bareMessage =
            MessageBuilder.withPayload("ask what is kotlin")
                .setHeader(Provenance.ADDRESSED, true)
                .setHeader(Provenance.BOT_NICK, "nevet")
                .build()

        val result = askOperation.execute(bareMessage)
        assertInstanceOf(OperationResult.Success::class.java, result)
    }
}
