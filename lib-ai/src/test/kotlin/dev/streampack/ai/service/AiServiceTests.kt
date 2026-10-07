/* Joseph B. Ottinger (C)2026 */
package dev.streampack.ai.service

import dev.streampack.ai.config.AiProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt

class AiServiceTests {
    private fun service(answer: (Prompt) -> ChatResponse) =
        AiService(
            object : ChatModel {
                override fun call(prompt: Prompt): ChatResponse = answer(prompt)
            },
            AiProperties(enabled = true, apiKey = "test"),
        )

    @Test
    fun `an answer is returned`() {
        val ai = service { ChatResponse(listOf(Generation(AssistantMessage("cheap, mostly")))) }
        assertEquals("cheap, mostly", ai.prompt("be brief", "the price of tea in China?"))
    }

    @Test
    fun `a failed call is null, not thrown`() {
        val ai = service { throw IllegalStateException("the API is down") }
        assertNull(ai.prompt("be brief", "anything"))
    }

    @Test
    fun `a missing class is null too, not an error that escapes every handler`() {
        val ai = service {
            throw NoClassDefFoundError("com/fasterxml/jackson/module/kotlin/ExtensionsKt")
        }
        assertNull(ai.prompt("be brief", "anything"))
    }

    private fun thinking(text: String) =
        Generation(
            AssistantMessage.builder().content(text).properties(mapOf("signature" to "sig")).build()
        )

    private fun text(text: String) = Generation(AssistantMessage(text))

    @Test
    fun `the answer is the text after the model's thinking, not the thinking`() {
        val ai = service { ChatResponse(listOf(thinking(""), text("Cheap, mostly."))) }
        assertEquals("Cheap, mostly.", ai.prompt("be brief", "the price of tea?"))

        val shown = service { ChatResponse(listOf(thinking("Let me consider..."), text("Cheap."))) }
        assertEquals("Cheap.", shown.prompt("be brief", "the price of tea?"))
    }

    @Test
    fun `an answer in several text blocks is joined`() {
        val ai = service { ChatResponse(listOf(text("Cheap,"), thinking(""), text("mostly."))) }
        assertEquals("Cheap,\nmostly.", ai.prompt("be brief", "the price of tea?"))
    }

    @Test
    fun `thinking with no answer is no answer, not an empty one`() {
        val ai = service { ChatResponse(listOf(thinking("long reasoning..."), text(""))) }
        assertNull(ai.prompt("be brief", "the price of tea?"))
        assertNull(service { ChatResponse(emptyList()) }.prompt("be brief", "anything"))
    }

    @Test
    fun `the defaults are Opus for general use and Haiku for moderation`() {
        val defaults = AiProperties()
        assertEquals("claude-opus-5-5", defaults.model)
        assertEquals("claude-haiku-4-5-20251001", defaults.moderationModel)
        assertEquals(1024, defaults.maxTokens)
        assertEquals(false, defaults.thinking)
        assertNull(defaults.effort)
    }

    /** A chat model whose defaults are [defaults], keeping each prompt it's sent */
    private class RecordingChatModel(private val defaults: ChatOptions) : ChatModel {
        val prompts = mutableListOf<Prompt>()

        override fun getOptions(): ChatOptions = defaults

        override fun call(prompt: Prompt): ChatResponse {
            prompts += prompt
            return ChatResponse(listOf(Generation(AssistantMessage("ok"))))
        }
    }

    private val defaults = ChatOptions.builder().model("claude-opus-5-5").maxTokens(777).build()

    @Test
    fun `a prompt leaves the model to the chat model's defaults`() {
        val chatModel = RecordingChatModel(defaults)
        AiService(chatModel, AiProperties(enabled = true)).prompt("be brief", "hello")
        assertNull(chatModel.prompts.single().options)
    }

    @Test
    fun `a moderation prompt asks for the moderation model, keeping the other settings`() {
        val chatModel = RecordingChatModel(defaults)
        val ai = AiService(chatModel, AiProperties(enabled = true, moderationModel = "cheap-model"))
        assertEquals("ok", ai.moderation().prompt("be brief", "hello"))
        // Spring AI sends a prompt's options instead of the defaults, so they must be whole
        val options = chatModel.prompts.single().options!!
        assertEquals("cheap-model", options.model)
        assertEquals(777, options.maxTokens)
    }

    @Test
    fun `a moderation prompt uses the provider's options for the model when it has them`() {
        val chatModel = RecordingChatModel(defaults)
        val provided = ChatOptions.builder().model("cheap-model").build()
        val asked = mutableListOf<String>()
        val ai =
            AiService(
                chatModel,
                AiProperties(enabled = true, moderationModel = "cheap-model"),
                { model ->
                    asked += model
                    provided
                },
            )
        ai.moderation().promptWithFormat("be brief", "hello", "as JSON")
        assertEquals(listOf("cheap-model"), asked)
        assertSame(provided, chatModel.prompts.single().options)
    }
}
