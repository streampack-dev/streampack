/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

import java.util.UUID

/** Request to edit an existing comment's markdown content */
data class EditCommentRequest(val id: UUID, val markdownSource: String)
