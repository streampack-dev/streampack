/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

import io.swagger.v3.oas.annotations.media.Schema

/**
 * A derived summary for editor-side preview, and where it came from: `ai` when the model wrote it
 * (admins, with AI enabled; #102), `heuristic` otherwise.
 */
data class DeriveSummaryResponse(
    val summary: String,
    // Always sent, but optional in the spec: it was added later, and existing clients don't expect
    // it.
    @field:Schema(
        requiredMode = Schema.RequiredMode.NOT_REQUIRED,
        allowableValues = [SOURCE_AI, SOURCE_HEURISTIC],
    )
    val source: String = SOURCE_HEURISTIC,
) {
    companion object {
        const val SOURCE_AI = "ai"
        const val SOURCE_HEURISTIC = "heuristic"
    }
}
