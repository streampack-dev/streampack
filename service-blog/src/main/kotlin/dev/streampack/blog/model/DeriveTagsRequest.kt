/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

/** Internal request for deriving AI tag suggestions from editor content. */
data class DeriveTagsRequest(
    val title: String,
    val markdownSource: String,
    val existingTags: List<String> = emptyList(),
)
