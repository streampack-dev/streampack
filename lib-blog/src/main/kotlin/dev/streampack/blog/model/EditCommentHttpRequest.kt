/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

/** HTTP request body for editing a comment (id comes from path variable) */
data class EditCommentHttpRequest(val markdownSource: String)
