/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

import java.time.Instant
import java.util.UUID

/** HTTP request body for editing a post (id resolved from path) */
data class EditContentHttpRequest(
    val title: String? = "",
    val markdownSource: String? = "",
    val tags: List<String>? = emptyList(),
    val categoryIds: List<UUID>? = emptyList(),
    /** Optional UI-facing summary value. Persisted internally as excerpt. */
    val summary: String? = null,
    val publishedAt: Instant? = null,
    val sortOrder: Int? = null,
)
