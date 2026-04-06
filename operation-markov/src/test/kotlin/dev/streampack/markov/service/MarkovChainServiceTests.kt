/* Joseph B. Ottinger (C)2026 */
package dev.streampack.markov.service

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class MarkovChainServiceTests {

    @Autowired lateinit var markovChainService: MarkovChainService

    @Test
    fun `empty corpus returns null`() {
        assertNull(markovChainService.generate(emptyList()))
    }

    @Test
    fun `single-word corpus returns null`() {
        assertNull(markovChainService.generate(listOf("hello")))
    }

    @Test
    fun `two-word corpus returns null`() {
        assertNull(markovChainService.generate(listOf("hello world")))
    }

    @Test
    fun `corpus with enough data generates output`() {
        val messages =
            listOf(
                "the quick brown fox jumps over the lazy dog",
                "the dog barked at the fox in the yard",
                "a quick dog ran through the brown yard",
            )
        val result = markovChainService.generate(messages)
        assertNotNull(result)
        assertTrue(result!!.isNotBlank())
    }

    @Test
    fun `generated words come from the corpus`() {
        val messages =
            listOf(
                "alpha bravo charlie delta echo",
                "bravo charlie delta echo foxtrot",
                "charlie delta echo foxtrot golf",
            )
        val corpusWords = messages.flatMap { it.lowercase().split(" ") }.toSet()
        val result = markovChainService.generate(messages, 10)
        assertNotNull(result)
        val generatedWords = result!!.split(" ")
        for (word in generatedWords) {
            assertTrue(word in corpusWords, "Word '$word' not found in corpus")
        }
    }

    @Test
    fun `max words limits output length`() {
        val messages =
            listOf(
                "one two three four five six seven eight nine ten",
                "two three four five six seven eight nine ten eleven",
                "three four five six seven eight nine ten eleven twelve",
            )
        val result = markovChainService.generate(messages, 5)
        assertNotNull(result)
        assertTrue(result!!.split(" ").size <= 5)
    }
}
