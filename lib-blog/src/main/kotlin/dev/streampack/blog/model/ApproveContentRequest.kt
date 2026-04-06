/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

import java.time.Instant
import java.util.UUID

/** Request to transition a draft post to APPROVED with a publication timestamp */
data class ApproveContentRequest(val id: UUID, val publishedAt: Instant)
