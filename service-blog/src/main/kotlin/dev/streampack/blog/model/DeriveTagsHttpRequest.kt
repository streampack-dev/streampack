/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

/** HTTP request for deriving AI tag suggestions from draft content. */
data class DeriveTagsHttpRequest(
    val title: String? = "",
    val markdownSource: String? = "",
    val existingTags: List<String>? = emptyList(),
)
