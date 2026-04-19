/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

import java.util.UUID

/** Internal request to record a UI-driven access of a blog post. */
data class RecordPostAccessRequest(val id: UUID)
