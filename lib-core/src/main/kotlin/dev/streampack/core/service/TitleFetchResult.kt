/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

/** Structured title fetch result with trust metadata. */
data class TitleFetchResult(
    val title: String? = null,
    val finalUrl: String? = null,
    val warnings: List<String> = emptyList(),
    val certificateInvalid: Boolean = false,
)
