/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.model

/** Self-service request for a user to edit their own displayName or email */
data class EditProfileRequest(val displayName: String? = null, val email: String? = null)
