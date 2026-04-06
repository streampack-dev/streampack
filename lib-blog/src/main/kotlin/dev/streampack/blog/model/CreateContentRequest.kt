/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

/** Request to create a new blog post draft */
data class CreateContentRequest(
    val title: String,
    val markdownSource: String,
    val tags: List<String>? = emptyList(),
    val categoryIds: List<java.util.UUID>? = emptyList(),
    /** Optional UI-facing summary value, persisted internally as excerpt. */
    val summary: String? = null,
)
