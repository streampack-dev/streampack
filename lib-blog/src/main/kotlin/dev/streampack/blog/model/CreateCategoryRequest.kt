/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

import java.util.UUID

/** Admin request to create a new content category */
data class CreateCategoryRequest(val name: String, val parentId: UUID? = null)
