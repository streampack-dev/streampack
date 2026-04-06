/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

/** Internal request for heuristic tag suggestions from unsaved draft content. */
data class SuggestTagsRequest(
    val title: String,
    val markdownSource: String,
    val existingTags: List<String> = emptyList(),
)
