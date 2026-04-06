/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.model

/** Typed request to register a GitHub repository for watching */
data class AddRepoRequest(val ownerRepo: String, val token: String? = null)
