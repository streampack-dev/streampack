/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

import java.util.UUID

/** Sealed hierarchy for content retrieval requests across all protocols */
sealed interface FindContentRequest {
    data class FindBySlug(val path: String) : FindContentRequest

    data class FindById(val id: UUID) : FindContentRequest

    data class FindPublished(val page: Int = 0, val size: Int = 20) : FindContentRequest

    data class Search(val query: String, val page: Int = 0, val size: Int = 20) : FindContentRequest

    /** Fetch approved posts in a named category, ordered by sortOrder then publishedAt */
    data class FindByCategory(val categoryName: String, val page: Int = 0, val size: Int = 20) :
        FindContentRequest

    /** Fetch approved posts with a named tag, ordered by publishedAt */
    data class FindByTag(val tagName: String, val page: Int = 0, val size: Int = 20) :
        FindContentRequest

    /** Fetch a single approved page by slug from the _pages system category */
    data class FindPage(val slug: String) : FindContentRequest
}
