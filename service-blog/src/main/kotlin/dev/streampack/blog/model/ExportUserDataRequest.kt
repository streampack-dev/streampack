/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

/** Request to export a user's data. Null username means export own data. */
data class ExportUserDataRequest(val username: String? = null)
