/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.model

/**
 * Asks for a factoid drafted for [selector] (#132), [context] being the writing it came from (a
 * paragraph or a post), so the sense meant is the one defined.
 */
data class DeriveFactoidRequest(val selector: String, val context: String = "")

/**
 * A factoid drafted for an author to edit and save, never stored by drafting. [line] is how the bot
 * would say it, whole; [fits] when that's within the target, so it would be said as written.
 * [droppedUrls] are addresses the model gave that didn't answer.
 */
data class FactoidDraft(
    val selector: String,
    val text: String,
    val urls: List<String>,
    val tags: List<String>,
    val seeAlso: List<String>,
    val line: String,
    val lineLength: Int,
    val fits: Boolean,
    val droppedUrls: List<String> = emptyList(),
)
