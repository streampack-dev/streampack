/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

/** Request to erase a user account. Null username means self-erasure. */
data class DeleteAccountRequest(val username: String? = null)
