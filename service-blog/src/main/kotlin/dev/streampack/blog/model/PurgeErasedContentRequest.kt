/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

import java.util.UUID

/** Admin request to hard-delete all content belonging to an erased user sentinel */
data class PurgeErasedContentRequest(val sentinelUserId: UUID)
