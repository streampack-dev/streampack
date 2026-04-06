/* Joseph B. Ottinger (C)2026 */
package dev.streampack.ideas.model

import dev.streampack.blog.model.CreateContentRequest

/** Per-channel article idea session state, serialized to JSONB via ProvenanceStateService */
data class IdeaSessionState(
    val title: String,
    val contentBlocks: List<String> = emptyList(),
    val submitterName: String,
    val sourceProvenance: String,
    val startedAt: Long,
    val includeAi: Boolean = false,
    val hasLogs: Boolean = false,
) {
    /** Builds the markdown body with attribution footer for draft creation */
    fun buildMarkdownBody(
        includeAttribution: Boolean = true,
        aiSummary: String? = null,
        aiTags: List<String> = emptyList(),
    ): String {
        val body =
            if (contentBlocks.isNotEmpty()) {
                contentBlocks.joinToString("\n\n")
            } else {
                ""
            }
        val aiSection =
            if (!aiSummary.isNullOrBlank() || aiTags.isNotEmpty()) {
                buildString {
                    append("\n\n## AI Draft Summary (Generated)\n")
                    if (!aiSummary.isNullOrBlank()) {
                        append("\n")
                        append(aiSummary.trim())
                        append("\n")
                    }
                    if (aiTags.isNotEmpty()) {
                        append("\nSuggested tags: ")
                        append(aiTags.joinToString(", "))
                        append("\n")
                    }
                    append(
                        "\n_Review before publishing. This section is machine-generated and can be edited or removed._"
                    )
                }
            } else {
                ""
            }
        val attribution =
            if (includeAttribution) "\n\n---\n*Contributed by $submitterName via $sourceProvenance*"
            else ""
        return (body + aiSection + attribution).trimStart()
    }

    /** Converts this session into a CreateContentRequest for dispatch via EventGateway */
    fun toCreateContentRequest(
        includeAttribution: Boolean = true,
        aiSummary: String? = null,
        aiTags: List<String> = emptyList(),
    ): CreateContentRequest =
        CreateContentRequest(
            title = title,
            markdownSource = buildMarkdownBody(includeAttribution, aiSummary, aiTags),
            tags = listOf("_idea"),
        )

    companion object {
        const val STATE_KEY = "article-idea"
    }
}
