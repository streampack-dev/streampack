/* Joseph B. Ottinger (C)2026 */
package dev.streampack.dictionary.service

import dev.streampack.dictionary.StubServer
import dev.streampack.dictionary.dictionaryJson
import dev.streampack.dictionary.freeDictionaryJson
import dev.streampack.dictionary.model.DictionaryResult
import java.time.Duration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/** Each dictionary's answers read as found, unknown or failed (#202) */
class DictionaryProvidersTests {
    private val stub = StubServer()
    private val http = DictionaryHttp(Duration.ofMillis(500))
    private val free = FreeDictionaryApiProvider(http, "${stub.url}/free")
    private val dev = DictionaryApiDevProvider(http, "${stub.url}/dev")

    @AfterEach fun tearDown() = stub.close()

    private fun found(answer: DictionaryAnswer): DictionaryResult =
        assertInstanceOf(DictionaryAnswer.Found::class.java, answer).result

    @Test
    fun `FreeDictionaryAPI's definition carries its credit and the Wiktionary page`() {
        stub.answer(
            "/free/quixotic",
            200,
            freeDictionaryJson("quixotic", "adjective", "Idealistic."),
        )

        val result = found(free.lookup("quixotic"))
        assertEquals("quixotic", result.word)
        assertEquals("adjective", result.partOfSpeech)
        assertEquals("Idealistic.", result.definition)
        assertEquals(
            "(Wiktionary via FreeDictionaryAPI.com, CC BY-SA 4.0: https://en.wiktionary.org/wiki/quixotic)",
            result.credit,
        )
    }

    @Test
    fun `FreeDictionaryAPI's empty entries are an unknown word`() {
        stub.answer("/free/zzqxvbn", 200, freeDictionaryJson("zzqxvbn"))
        assertEquals(DictionaryAnswer.Unknown, free.lookup("zzqxvbn"))
    }

    @Test
    fun `a phrase is asked for as one path segment, the space encoded`() {
        stub.answer(
            "/free/ice cream",
            200,
            freeDictionaryJson("ice cream", "noun", "A frozen dessert."),
        )
        assertEquals("A frozen dessert.", found(free.lookup("ice cream")).definition)
    }

    @Test
    fun `a limit, an error or garbage is a failure, not an unknown word`() {
        stub.answer("/free/busy", 429)
        stub.answer("/free/broken", 503)
        stub.answer("/free/garbage", 200, """{"word":"garbage"}""")
        listOf("busy", "broken", "garbage").forEach {
            assertInstanceOf(DictionaryAnswer.Failed::class.java, free.lookup(it), it)
        }
    }

    @Test
    fun `a provider that doesn't answer in time throws, for the service to count as failed`() {
        stub.hang("/free/slow")
        val started = System.nanoTime()
        val failed = runCatching { free.lookup("slow") }.isFailure
        assertEquals(true, failed)
        assert(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(3))
    }

    @Test
    fun `dictionaryapi dev's definition carries its credit`() {
        stub.answer(
            "/dev/ephemeral",
            200,
            dictionaryJson("ephemeral", "adjective", "lasting a short time."),
        )

        val result = found(dev.lookup("ephemeral"))
        assertEquals("lasting a short time.", result.definition)
        assertEquals(
            "(Wiktionary via dictionaryapi.dev, CC BY-SA 3.0: https://en.wiktionary.org/wiki/ephemeral)",
            result.credit,
        )
    }

    @Test
    fun `dictionaryapi dev without a source falls back to the Wiktionary page`() {
        stub.answer(
            "/dev/ice cream",
            200,
            dictionaryJson("ice cream", "noun", "a frozen dessert.", credited = false),
        )
        assertEquals(
            "(Wiktionary via dictionaryapi.dev, CC BY-SA: https://en.wiktionary.org/wiki/ice_cream)",
            found(dev.lookup("ice cream")).credit,
        )
    }

    @Test
    fun `dictionaryapi dev's 404 is an unknown word, and its 5xx a failure`() {
        stub.answer("/dev/xyzzy", 404, """{"title":"No Definitions Found"}""")
        stub.answer("/dev/down", 502)
        assertEquals(DictionaryAnswer.Unknown, dev.lookup("xyzzy"))
        assertInstanceOf(DictionaryAnswer.Failed::class.java, dev.lookup("down"))
    }

    @Test
    fun `a page link with a space gets an underscore, so the link holds in a chat line`() {
        stub.answer(
            "/free/ice cream",
            200,
            // The response's page link carries the space, as FreeDictionaryAPI's does
            freeDictionaryJson("ice cream", "noun", "A frozen dessert."),
        )
        assertEquals(
            "(Wiktionary via FreeDictionaryAPI.com, CC BY-SA 4.0: https://en.wiktionary.org/wiki/ice_cream)",
            found(free.lookup("ice cream")).credit,
        )
    }
}
