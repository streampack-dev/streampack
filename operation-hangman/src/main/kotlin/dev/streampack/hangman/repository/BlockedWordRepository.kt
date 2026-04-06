/* Joseph B. Ottinger (C)2026 */
package dev.streampack.hangman.repository

import dev.streampack.hangman.entity.BlockedWord
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface BlockedWordRepository : JpaRepository<BlockedWord, UUID> {
    fun existsByWord(word: String): Boolean

    fun findByWord(word: String): BlockedWord?

    fun deleteByWord(word: String)
}
