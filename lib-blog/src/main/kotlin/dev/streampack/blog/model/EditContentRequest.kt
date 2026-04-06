/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

import dev.streampack.blog.entity.Post
import dev.streampack.blog.service.MarkdownRenderingService
import java.time.Instant
import java.util.*

/** Request to modify an existing post's title and content */
data class EditContentRequest(
    val id: UUID,
    val title: String?,
    val markdownSource: String?,
    val tags: List<String>? = emptyList(),
    val categoryIds: List<UUID>? = emptyList(),
    /** Optional UI-facing summary value, persisted internally as excerpt. */
    val summary: String? = null,
    val publishedAt: Instant? = null,
    val sortOrder: Int? = null,
) {
    fun applyTo(post: Post, markdownRenderingService: MarkdownRenderingService): Post {
        val resolvedTitle = title ?: post.title
        val resolvedMarkdown = markdownSource ?: post.markdownSource
        val providedSummary = summary?.trim().orEmpty()
        val excerpt =
            if (providedSummary.isNotBlank()) {
                providedSummary
            } else {
                markdownRenderingService.excerpt(resolvedMarkdown).ifBlank { resolvedTitle.trim() }
            }
        return post.copy(
            title = resolvedTitle,
            markdownSource = resolvedMarkdown,
            renderedHtml = markdownRenderingService.render(resolvedMarkdown),
            excerpt = excerpt,
            publishedAt = publishedAt ?: post.publishedAt,
            sortOrder = sortOrder ?: post.sortOrder,
            updatedAt = Instant.now(),
        )
    }
}
