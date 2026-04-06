/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

import java.util.UUID

/**
 * Admin request to soft-delete a category (hidden from selection, existing associations preserved)
 */
data class SoftDeleteCategoryRequest(val id: UUID)
