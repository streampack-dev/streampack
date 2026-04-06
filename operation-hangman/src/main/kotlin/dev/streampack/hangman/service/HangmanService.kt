/* Joseph B. Ottinger (C)2026 */
package dev.streampack.hangman.service

import dev.streampack.hangman.entity.BlockedWord
import dev.streampack.hangman.repository.BlockedWordRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Coordinates word selection with blocklist filtering */
@Service
class HangmanService(
    private val wordSource: WordSource,
    private val blockedWordRepository: BlockedWordRepository,
) {
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
            "Could not find a non-blocked word after $MAX_ATTEMPTS attempts"
        )
    }

    fun isBlocked(word: String): Boolean = blockedWordRepository.existsByWord(word.lowercase())

    @Transactional
    fun blockWord(word: String) {
        val normalized = word.lowercase()
        if (!blockedWordRepository.existsByWord(normalized)) {
            blockedWordRepository.save(BlockedWord(word = normalized))
        }
    }

    @Transactional
    fun unblockWord(word: String) {
        blockedWordRepository.deleteByWord(word.lowercase())
    }

    companion object {
        const val MAX_ATTEMPTS = 100
    }
}
