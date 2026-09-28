/* Joseph B. Ottinger (C)2026 */
package dev.streampack.taxonomy.model

import io.swagger.v3.oas.annotations.media.Schema

/** Named taxonomy term with aggregate usage count. */
data class TaxonomyTermCount(val name: String, val count: Long)

/** Aggregate taxonomy payload for API/UI use. Terms starting with `_` are never included. */
@Schema(description = "Usage counts per taxonomy term. Terms starting with _ are never included.")
data class TaxonomySnapshot(
    @field:Schema(
        description =
            "Published posts carrying each tag: approved, published by now, not deleted and not in a " +
                "hidden category. Matches the totalCount of GET /posts?tag=."
    )
    val tags: Map<String, Long>,
    @field:Schema(description = "Posts in each category.") val categories: Map<String, Long>,
    @field:Schema(description = "The union of tags, factoidTags and categories, summed per term.")
    val aggregate: Map<String, Long>,
    @field:Schema(description = "Factoids carrying each tag.")
    val factoidTags: Map<String, Long> = emptyMap(),
)
