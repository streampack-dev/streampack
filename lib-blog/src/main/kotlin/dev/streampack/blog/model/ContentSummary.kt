/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

import java.time.Instant
import java.util.UUID

/** Lightweight post representation for listing pages */
data class ContentSummary(
    val id: UUID,
    val title: String,
    val slug: String,
    val excerpt: String?,
    val authorDisplayName: String,
    val publishedAt: Instant?,
    val sortOrder: Int = 0,
    val commentCount: Int = 0,
    val tags: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
)
