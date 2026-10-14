/* Joseph B. Ottinger (C)2026 */
package dev.streampack.dictionary.service

import dev.streampack.dictionary.model.DictionaryResult
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Providers asked in order, the first answer settling it, a failed one skipped a while (#202) */
class DictionaryLookupServiceTests {

    private class Fake(override val name: String, override val order: Int) : DictionaryProvider {
        var answer: () -> DictionaryAnswer = { DictionaryAnswer.Unknown }
        var asked = 0

        override fun lookup(word: String): DictionaryAnswer {
            asked++
            return answer()
        }
    }

    private class MovableClock(var now: Instant = Instant.parse("2026-10-14T12:00:00Z")) : Clock() {
        override fun instant() = now

        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?) = this
    }

    private fun found(by: String) =
        DictionaryAnswer.Found(DictionaryResult("word", "noun", "defined by $by", "($by)"))

    private val first = Fake("first", 10)
    private val second = Fake("second", 20)
    private val clock = MovableClock()
    // Given out of order: the service sorts them
    private val service =
        DictionaryLookupService(listOf(second, first), Duration.ofMinutes(15), clock)

    @Test
    fun `the first provider's definition is the answer`() {
        first.answer = { found("first") }
        second.answer = { found("second") }

        assertEquals("defined by first", service.lookup("word")?.definition)
        assertEquals(0, second.asked)
    }

    @Test
    fun `an unknown word ends the lookup, without asking the next provider`() {
        first.answer = { DictionaryAnswer.Unknown }
        second.answer = { found("second") }

        assertNull(service.lookup("xyzzy"))
        assertEquals(0, second.asked)
    }

    @Test
    fun `a failure, or a provider that throws, falls through to the next`() {
        first.answer = { DictionaryAnswer.Failed("HTTP 503") }
        second.answer = { found("second") }
        assertEquals("defined by second", service.lookup("word")?.definition)

        service.resetBreakers()
        first.answer = { throw java.net.http.HttpTimeoutException("request timed out") }
        assertEquals("defined by second", service.lookup("word")?.definition)
    }

    @Test
    fun `a failed provider is skipped for fifteen minutes, then asked again`() {
        first.answer = { DictionaryAnswer.Failed("HTTP 503") }
        second.answer = { found("second") }
        service.lookup("word")
        assertEquals(1, first.asked)

        clock.now = clock.now.plus(Duration.ofMinutes(14))
        service.lookup("word")
        assertEquals(1, first.asked)

        clock.now = clock.now.plus(Duration.ofMinutes(2))
        first.answer = { found("first") }
        assertEquals("defined by first", service.lookup("word")?.definition)
        assertEquals(2, first.asked)
    }

    @Test
    fun `nobody answering is no definition`() {
        first.answer = { DictionaryAnswer.Failed("down") }
        second.answer = { DictionaryAnswer.Failed("down") }
        assertNull(service.lookup("word"))
    }

    @Test
    fun `each case is asked for until one is found, as typed first`() {
        val asked = mutableListOf<String>()
        val wiktionary =
            object : DictionaryProvider {
                override val name = "cased"
                override val order = 1

                override fun lookup(word: String): DictionaryAnswer {
                    asked += word
                    return if (word == "JSON") found("cased") else DictionaryAnswer.Unknown
                }
            }
        val cased = DictionaryLookupService(listOf(wiktionary), Duration.ofMinutes(15), clock)

        assertEquals("defined by cased", cased.lookup("json")?.definition)
        assertEquals(listOf("json", "Json", "JSON"), asked)
    }

    @Test
    fun `the cases are each tried once`() {
        assertEquals(
            listOf("ice cream", "Ice cream", "ICE CREAM"),
            DictionaryLookupService.cases("ice cream"),
        )
        assertEquals(listOf("JSON", "json", "Json"), DictionaryLookupService.cases("JSON"))
    }
}
