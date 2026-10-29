/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.extensions

/** Trims and collapses internal whitespace runs to single spaces */
fun String.compress(): String = this.trim().replace(Regex("\\s+"), " ")

/** Formats a name as an English possessive: "chris" -> "chris'", "joe" -> "joe's" */
fun String.possessive(): String {
    if (this.isEmpty()) return this
    return if (this.last().lowercaseChar() == 's') "${this}'" else "${this}'s"
}

/** Returns true if the last character is sentence-ending punctuation */
fun String.endsWithPunctuation(): Boolean {
    if (this.isEmpty()) return false
    return this.last() in ".!?;:"
}
