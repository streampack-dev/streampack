/* Joseph B. Ottinger (C)2026 */
package dev.streampack.dictionary.service

import dev.streampack.dictionary.model.DictionaryResult

/**
 * One source of definitions (#202). [DictionaryLookupService] asks them in [order], lowest first,
 * and the first that answers [DictionaryAnswer.Found] or [DictionaryAnswer.Unknown] settles the
 * lookup; one that [DictionaryAnswer.Failed] is skipped for a while.
 */
interface DictionaryProvider {
    /** How the log names it */
    val name: String

    /** Its place in the order: lower is asked first */
    val order: Int

    fun lookup(word: String): DictionaryAnswer
}

sealed interface DictionaryAnswer {
    data class Found(val result: DictionaryResult) : DictionaryAnswer

    /** The provider answered, and has no such word: the lookup ends here */
    data object Unknown : DictionaryAnswer

    /** The provider couldn't answer: down, slow, limited or garbled. The next one is asked. */
    data class Failed(val reason: String) : DictionaryAnswer
}
