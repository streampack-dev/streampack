/* Joseph B. Ottinger (C)2026 */
package dev.streampack.dictionary.service

import dev.streampack.dictionary.model.DictionaryResult
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * Looks a word up in each [DictionaryProvider] in turn (#202). The first that finds the word, or
 * says there's no such word, settles it. Wiktionary is case-sensitive ("JSON", "Kubernetes",
 * "ephemeral"), so each provider is asked for the word as typed, then lowercase, Capitalized and
 * UPPERCASE, until one is found; it says there's no such word only when none is. One that fails
 * (down, slow, limited, garbled) is skipped for [breakFor], 15 minutes by default
 * (`streampack.dictionary.break-for`), so an outage costs one timeout rather than one per lookup.
 * Tripping and recovering are logged at WARN, so an outage is noticed.
 */
@Service
open class DictionaryLookupService(
    providers: List<DictionaryProvider>,
    private val breakFor: Duration,
    private val clock: Clock,
) {
    @Autowired
    constructor(
        providers: List<DictionaryProvider>,
        @Value("\${streampack.dictionary.break-for:15m}") breakFor: Duration,
    ) : this(providers, breakFor, Clock.systemUTC())

    private val logger = LoggerFactory.getLogger(DictionaryLookupService::class.java)
    private val providers = providers.sortedBy { it.order }

    /** When each tripped provider may be asked again */
    private val brokenUntil = ConcurrentHashMap<String, Instant>()

    /** The first definition of [word], or null if there's no such word or nobody could answer */
    open fun lookup(word: String): DictionaryResult? {
        for (provider in providers) {
            val until = brokenUntil[provider.name]
            if (until != null && clock.instant().isBefore(until)) continue
            when (val answer = askEachCase(provider, word)) {
                is DictionaryAnswer.Found -> {
                    recovered(provider)
                    return answer.result
                }
                DictionaryAnswer.Unknown -> {
                    recovered(provider)
                    return null
                }
                is DictionaryAnswer.Failed -> trip(provider, answer.reason)
            }
        }
        logger.warn("No dictionary could answer for '{}'", word)
        return null
    }

    /** Forgets every tripped provider: for tests sharing one service */
    internal fun resetBreakers() = brokenUntil.clear()

    /** [provider]'s answer for the first of [word]'s cases it finds; a failure stops at once */
    private fun askEachCase(provider: DictionaryProvider, word: String): DictionaryAnswer {
        for (form in cases(word)) {
            val answer = ask(provider, form)
            if (answer != DictionaryAnswer.Unknown) return answer
        }
        return DictionaryAnswer.Unknown
    }

    private fun ask(provider: DictionaryProvider, word: String): DictionaryAnswer =
        try {
            provider.lookup(word)
        } catch (e: Exception) {
            DictionaryAnswer.Failed(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        }

    private fun trip(provider: DictionaryProvider, reason: String) {
        val first = brokenUntil.put(provider.name, clock.instant().plus(breakFor)) == null
        logger.warn(
            "Dictionary {} failed ({}){}; skipping it for {}",
            provider.name,
            reason,
            if (first) "" else " again",
            breakFor,
        )
    }

    private fun recovered(provider: DictionaryProvider) {
        if (brokenUntil.remove(provider.name) != null) {
            logger.warn("Dictionary {} is answering again", provider.name)
        }
    }

    companion object {
        /** [word] as typed, then lowercase, Capitalized and UPPERCASE, each once */
        internal fun cases(word: String): List<String> =
            listOf(
                    word,
                    word.lowercase(),
                    word.lowercase().replaceFirstChar { it.uppercaseChar() },
                    word.uppercase(),
                )
                .distinct()
    }
}
