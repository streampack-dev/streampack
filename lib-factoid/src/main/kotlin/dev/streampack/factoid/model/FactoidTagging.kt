/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.model

import java.time.Instant

/** Asks for every tagged factoid's tags and times, for the Atlas (ui-pudl#184). */
data object FindFactoidTaggingRequest

/**
 * Every factoid that carries tags: what it's called, its tags and when it was taught and changed.
 */
data class FactoidTagging(val entries: List<FactoidTagEntry>)

/**
 * A factoid's tags, as the taxonomy counts them: its `tags` attribute split on commas, trimmed and
 * lowercased, with blanks and `_` tags left out.
 */
data class FactoidTagEntry(
    val selector: String,
    val tags: List<String>,
    val createdAt: Instant,
    val updatedAt: Instant,
)
