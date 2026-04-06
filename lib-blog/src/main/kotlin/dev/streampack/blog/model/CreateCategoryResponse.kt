/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

import java.util.UUID

/** Result of successfully creating a new category */
data class CreateCategoryResponse(
    val id: UUID,
    val name: String,
    val slug: String,
    val parentName: String?,
)
