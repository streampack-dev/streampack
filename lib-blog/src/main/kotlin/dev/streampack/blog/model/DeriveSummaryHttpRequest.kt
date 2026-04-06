/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

/** HTTP body for deriving a non-persistent summary from draft content. */
data class DeriveSummaryHttpRequest(val title: String? = "", val markdownSource: String? = "")
