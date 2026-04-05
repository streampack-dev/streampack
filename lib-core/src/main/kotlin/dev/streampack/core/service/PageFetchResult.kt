/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

/** Structured HTTP fetch result with trust metadata. */
data class PageFetchResult(
    val body: String? = null,
    val finalUrl: String? = null,
    val statusCode: Int? = null,
    val warnings: List<String> = emptyList(),
    val certificateInvalid: Boolean = false,
) {
    val success: Boolean
        get() = body != null
}
