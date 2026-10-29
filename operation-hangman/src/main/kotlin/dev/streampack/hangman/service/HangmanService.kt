/* Joseph B. Ottinger (C)2026 */
package dev.streampack.hangman.service

import dev.streampack.core.service.ExtensionDataStore
import dev.streampack.hangman.HangmanExtension
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Coordinates word selection with blocklist filtering; the blocklist is in the server's store
 * (#294)
 */
@Service
class HangmanService(private val wordSource: WordSource, private val store: ExtensionDataStore) {
    private val logger = LoggerFactory.getLogger(HangmanService::class.java)

    /** Selects a random word that is not blocked, with a max-attempts guard */
    fun selectWord(): String {
        repeat(MAX_ATTEMPTS) {
            val candidate = wordSource.randomWord()
            if (!isBlocked(candidate)) {
                return candidate
            }
            logger.debug("Rejected blocked word, retrying")
        }
        throw IllegalStateException(
            "Could not find a non-blocked word after $MAX_ATTEMPTS attempts",
        )
    }

    fun isBlocked(word: String): Boolean =
        store.get(HangmanExtension.NAME, BLOCKED, word.lowercase()) != null

    fun blockWord(word: String) {
        store.put(HangmanExtension.NAME, BLOCKED, word.lowercase(), emptyMap())
    }

    fun unblockWord(word: String) {
        store.delete(HangmanExtension.NAME, BLOCKED, word.lowercase())
    }

    companion object {
        const val MAX_ATTEMPTS = 100
        /** The blocked words, one empty document per word, by the word in lower case. */
        const val BLOCKED = "blocked-words"
    }
}
