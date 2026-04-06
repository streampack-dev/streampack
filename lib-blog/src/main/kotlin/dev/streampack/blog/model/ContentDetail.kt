/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

import java.time.Instant
import java.util.UUID

/** Full post representation for single-post views */
data class ContentDetail(
    val id: UUID,
    val title: String,
    val slug: String,
    val renderedHtml: String,
    val excerpt: String?,
    val authorId: UUID?,
    val authorDisplayName: String,
    val status: PostStatus,
    val publishedAt: Instant?,
    val sortOrder: Int = 0,
    val createdAt: Instant,
    val updatedAt: Instant,
    val commentCount: Int = 0,
    val tags: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val markdownSource: String? = null,
)
