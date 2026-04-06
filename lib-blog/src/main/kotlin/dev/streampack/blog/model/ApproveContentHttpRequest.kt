/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

import java.time.Instant

/** HTTP request body for approving a post (id resolved from path) */
data class ApproveContentHttpRequest(val publishedAt: Instant)
