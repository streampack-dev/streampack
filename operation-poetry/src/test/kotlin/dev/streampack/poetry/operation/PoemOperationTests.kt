/* Joseph B. Ottinger (C)2026 */
package dev.streampack.poetry.operation

import dev.streampack.ai.config.AiProperties
import dev.streampack.ai.service.AiService
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.poetry.model.PoemAnalysisRequest
import dev.streampack.poetry.model.PoemRequest
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
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
class PoemOperationTests {

    @TestConfiguration
    class MockAiConfig {

        /** Provides a mock AiService that returns predictable text */
        @Bean
        @Primary
        fun aiService(): AiService {
            val mockChatModel = Mockito.mock(ChatModel::class.java)
            val mockProperties = AiProperties(enabled = true)
            return object : AiService(mockChatModel, mockProperties) {
                override fun prompt(systemInstruction: String, userPrompt: String): String {
                    return if (systemInstruction.contains("poet")) {
                        "Roses are red,\nViolets are blue,\n$userPrompt is the topic,\nAnd this poem is new."
                    } else {
                        "This poem uses AABB rhyme scheme with iambic tetrameter."
                    }
                }
            }
        }
    }

    @Autowired lateinit var eventGateway: EventGateway

    @Autowired lateinit var poemOperation: PoemOperation

    @Autowired lateinit var poemAnalysisOperation: PoemAnalysisOperation

    private fun provenance() =
        Provenance(protocol = Protocol.CONSOLE, serviceId = "", replyTo = "local")

    private fun message(text: String) =
        MessageBuilder.withPayload(text)
            .setHeader(Provenance.HEADER, provenance())
            .setHeader(Provenance.ADDRESSED, true)
            .build()

    @Test
    fun `poem command generates poem with loopback`() {
        val result = poemOperation.execute(message("poem roses"))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val success = result as OperationResult.Success
        assertTrue(success.loopback, "Poem result should have loopback=true")
        val output = success.payload.toString()
        assertTrue(output.startsWith("a poem: "), "Poem should start with 'a poem: '")
        assertTrue(output.contains("roses"))
        assertTrue(output.contains("/"), "Verses should be separated by slashes")
    }

    @Test
    fun `poem command is case insensitive`() {
        val result = poemOperation.execute(message("Poem sunsets"))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val success = result as OperationResult.Success
        val output = success.payload.toString()
        assertTrue(output.startsWith("a poem: "), "Poem should start with 'a poem: '")
        assertTrue(output.contains("sunsets"))
    }

    @Test
    fun `bare poem command is not handled`() {
        val result = poemOperation.execute(message("poem"))
        assertTrue(result == null, "Bare 'poem' should not be handled")
    }

    @Test
    fun `poem with only whitespace after command is not handled`() {
        val result = poemOperation.execute(message("poem   "))
        assertTrue(result == null, "Poem with blank topic should not be handled")
    }

    @Test
    fun `non-poem message is not handled`() {
        val result = poemOperation.execute(message("weather tallahassee"))
        assertTrue(result == null, "Non-poem message should not be handled")
    }

    @Test
    fun `analysis operation handles poem-prefixed text`() {
        val poemText = "a poem: Roses are red,/Violets are blue,/This is a poem,/Just for you."
        val result = poemAnalysisOperation.execute(message(poemText))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val success = result as OperationResult.Success
        assertTrue(success.payload.toString().startsWith("Analysis:"))
        assertTrue(!success.loopback, "Analysis should not loop back")
    }

    @Test
    fun `analysis operation ignores text without poem prefix`() {
        val result = poemAnalysisOperation.execute(message("just a regular command"))
        assertTrue(result == null, "Text without 'a poem:' prefix should not trigger analysis")
    }

    @Test
    fun `analysis operation ignores bare poem prefix`() {
        val result = poemAnalysisOperation.execute(message("a poem:   "))
        assertTrue(result == null, "Bare 'a poem:' with no content should not trigger analysis")
    }

    @Test
    fun `typed PoemRequest bypasses translation`() {
        val typedMessage =
            MessageBuilder.withPayload(PoemRequest("mountains") as Any)
                .setHeader(Provenance.HEADER, provenance())
                .build()

        val result = poemOperation.execute(typedMessage)

        assertInstanceOf(OperationResult.Success::class.java, result)
        val success = result as OperationResult.Success
        val output = success.payload.toString()
        assertTrue(output.startsWith("a poem: "), "Poem should start with 'a poem: '")
        assertTrue(output.contains("mountains"))
    }

    @Test
    fun `typed PoemAnalysisRequest bypasses translation`() {
        val typedMessage =
            MessageBuilder.withPayload(
                    PoemAnalysisRequest(
                        "A short poem\nWith two lines\nAnd a third for good measure"
                    )
                        as Any
                )
                .setHeader(Provenance.HEADER, provenance())
                .build()

        val result = poemAnalysisOperation.execute(typedMessage)

        assertInstanceOf(OperationResult.Success::class.java, result)
        assertNotNull(result)
    }
}
