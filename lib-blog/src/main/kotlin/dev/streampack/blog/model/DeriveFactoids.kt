/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

/** A draft to find factoid mentions in (#130). */
data class DeriveFactoidsRequest(val markdownSource: String)

/** HTTP request body for [DeriveFactoidsRequest]. */
data class DeriveFactoidsHttpRequest(val markdownSource: String? = "")

/**
 * The factoids a draft mentions ([mentions]), and the `[[…]]` links it has to factoids that don't
 * exist ([missing]). Nothing is stored.
 */
data class DeriveFactoidsResponse(
    val mentions: List<FactoidMention>,
    val missing: List<MissingFactoid>,
)

/**
 * A factoid the draft mentions: [term] as first written, how often, the factoid's [definition], and
 * whether the draft already links it somewhere. Accepting wraps the first mention as
 * `[[term|selector]]`, or `[[selector]]` when they're the same.
 */
data class FactoidMention(
    val selector: String,
    val term: String,
    val occurrences: Int,
    val definition: String,
    val linked: Boolean,
)

/** A `[[…]]` in the draft naming no factoid: [term] as written, [selector] as linked. */
data class MissingFactoid(val selector: String, val term: String)
