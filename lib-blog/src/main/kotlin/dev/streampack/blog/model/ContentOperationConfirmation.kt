/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

import java.util.UUID

/** Confirmation response for retract and remove operations */
data class ContentOperationConfirmation(val id: UUID, val message: String)
