/* Joseph B. Ottinger (C)2026 */
package dev.streampack.dictionary.model

/**
 * A definition and the credit its source asks for, e.g. "(Wiktionary via FreeDictionaryAPI.com, CC
 * BY-SA 4.0: https://en.wiktionary.org/wiki/ephemeral)". The credit goes everywhere the definition
 * does, the factoid it's cached as included.
 */
data class DictionaryResult(
    val word: String,
    val partOfSpeech: String,
    val definition: String,
    val credit: String,
)
