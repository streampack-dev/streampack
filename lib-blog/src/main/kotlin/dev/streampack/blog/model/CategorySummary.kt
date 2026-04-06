/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

import java.util.UUID

/** Lightweight category representation for listing endpoints */
data class CategorySummary(
    val id: UUID,
    val name: String,
    val slug: String,
    val parentName: String?,
)
