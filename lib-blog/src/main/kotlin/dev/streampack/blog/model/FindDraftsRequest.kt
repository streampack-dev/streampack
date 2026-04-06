/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

/** Admin request to list unapproved posts for the review queue */
data class FindDraftsRequest(val page: Int = 0, val size: Int = 20, val deleted: Boolean = false)
