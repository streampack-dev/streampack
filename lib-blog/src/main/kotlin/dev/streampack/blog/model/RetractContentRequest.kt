/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

import java.util.UUID

/** Request for an author to withdraw their own draft (soft delete) */
data class RetractContentRequest(val id: UUID)
