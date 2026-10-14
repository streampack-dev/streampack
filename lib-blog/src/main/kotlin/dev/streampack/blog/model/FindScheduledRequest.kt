/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

/** Admin request to list scheduled posts: approved, with a publication date still to come (#207) */
data class FindScheduledRequest(val page: Int = 0, val size: Int = 20)
