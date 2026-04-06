/* Joseph B. Ottinger (C)2026 */
package dev.streampack.hangman.service

/** Provides random words for hangman games */
interface WordSource {
    fun randomWord(): String
}
