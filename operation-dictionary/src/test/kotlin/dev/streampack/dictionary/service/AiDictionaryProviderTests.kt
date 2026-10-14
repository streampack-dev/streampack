/* Joseph B. Ottinger (C)2026 */
package dev.streampack.dictionary.service

import dev.streampack.ai.config.AiProperties
import dev.streampack.ai.service.AiService
import dev.streampack.core.service.ThrottleService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.ai.chat.model.ChatModel

/** The AI fallback: the cheaper model, refusing non-words, credited, and limited (#202) */
class AiDictionaryProviderTests {
    /** An AiService answering [answer]; [cheap] is what moderation() gives, the cheaper model's */
    private open class FakeAi(val label: String) :
        AiService(Mockito.mock(ChatModel::class.java), AiProperties(enabled = true)) {
        var answer: String? = null
        val asked = mutableListOf<String>()
        var cheap: FakeAi? = null

        override fun moderation(): AiService = cheap ?: this

        override fun prompt(systemInstruction: String, userPrompt: String): String? {
            asked += userPrompt
            return answer
        }
    }

    private val cheap = FakeAi("cheap")
    private val ai = FakeAi("default").also { it.cheap = cheap }
    private val throttle = ThrottleService()
    private val provider = AiDictionaryProvider(ai, throttle)

    private fun answers(text: String?) {
        cheap.answer = text
    }

    @Test
    fun `a definition from the cheaper model is credited as AI-generated`() {
        answers("""{"partOfSpeech": "adjective", "definition": "Idealistic and impractical."}""")

        val result =
            assertInstanceOf(DictionaryAnswer.Found::class.java, provider.lookup("quixotic")).result
        assertEquals("adjective", result.partOfSpeech)
        assertEquals("Idealistic and impractical.", result.definition)
        assertEquals("(AI-generated)", result.credit)
        assertEquals(listOf("quixotic"), cheap.asked)
        assertTrue(ai.asked.isEmpty(), "the default model isn't asked")
    }

    @Test
    fun `an answer in a code fence is read`() {
        answers("```json\n{\"partOfSpeech\": \"adjective\", \"definition\": \"Idealistic.\"}\n```")
        assertInstanceOf(DictionaryAnswer.Found::class.java, provider.lookup("quixotic"))
    }

    @Test
    fun `a word the model says doesn't exist is unknown`() {
        answers("""{"unknown": true}""")
        assertEquals(DictionaryAnswer.Unknown, provider.lookup("quixotic"))
    }

    @Test
    fun `no answer, or one that isn't JSON, is a failure`() {
        answers(null)
        assertInstanceOf(DictionaryAnswer.Failed::class.java, provider.lookup("quixotic"))
        answers("Quixotic means idealistic.")
        assertInstanceOf(DictionaryAnswer.Failed::class.java, provider.lookup("quixotic"))
    }

    @Test
    fun `past sixty lookups in an hour it fails rather than asking`() {
        answers("""{"partOfSpeech": "adjective", "definition": "Idealistic."}""")
        repeat(60) {
            assertInstanceOf(DictionaryAnswer.Found::class.java, provider.lookup("quixotic"))
        }
        assertInstanceOf(DictionaryAnswer.Failed::class.java, provider.lookup("quixotic"))
    }
}
