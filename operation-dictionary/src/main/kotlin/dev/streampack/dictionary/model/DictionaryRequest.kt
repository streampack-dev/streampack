/* Joseph B. Ottinger (C)2026 */
package dev.streampack.dictionary.model

/** [word] as it was typed: Wiktionary has "JSON" and "Kubernetes", not "json" or "kubernetes" */
data class DictionaryRequest(val word: String) {

    /** The factoid-style selector, always lowercase, e.g. "define ephemeral" */
    val selector: String
        get() = "define ${word.lowercase()}"
}
