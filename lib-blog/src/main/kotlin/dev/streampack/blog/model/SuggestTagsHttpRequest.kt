/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

/** HTTP request body for heuristic tag suggestions from unsaved draft content. */
data class SuggestTagsHttpRequest(
    val title: String? = "",
    val markdownSource: String? = "",
    val existingTags: List<String>? = emptyList(),
)
