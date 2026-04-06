/* Joseph B. Ottinger (C)2026 */
package dev.streampack.dictionary.model

data class DictionaryRequest(val word: String) {

    /** The factoid-style selector, e.g. "define ephemeral" */
    val selector: String
        get() = "define $word"
}
