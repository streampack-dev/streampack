/* Joseph B. Ottinger (C)2026 */
package dev.streampack.dictionary.model

data class DictionaryResult(
    val word: String,
    val partOfSpeech: String,
    val definition: String,
    val sourceUrl: String,
    val license: String,
) {
    /** The attribution CC BY-SA asks for: where the definition came from, and under what license */
    val credit: String
        get() = "Source: $sourceUrl (Wiktionary, $license)"
}
