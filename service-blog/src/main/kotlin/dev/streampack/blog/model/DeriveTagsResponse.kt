/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

/** Derived tag suggestions returned to the UI (no persistence side effects). */
data class DeriveTagsResponse(val tags: List<String>)
