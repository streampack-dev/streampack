/* Joseph B. Ottinger (C)2026 */
package dev.streampack.taxonomy.model

/** Named taxonomy term with aggregate usage count. */
data class TaxonomyTermCount(val name: String, val count: Long)

/** Aggregate taxonomy payload for API/UI use. */
data class TaxonomySnapshot(
    val tags: Map<String, Long>,
    val categories: Map<String, Long>,
    val aggregate: Map<String, Long>,
)
