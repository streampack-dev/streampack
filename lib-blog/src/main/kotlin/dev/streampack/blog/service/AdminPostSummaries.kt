/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.service

import dev.streampack.blog.entity.Post
import dev.streampack.blog.model.ContentListResponse
import dev.streampack.blog.model.ContentSummary
import dev.streampack.blog.model.PostState
import dev.streampack.blog.repository.PostCategoryRepository
import dev.streampack.blog.repository.PostTagRepository
import dev.streampack.blog.repository.SlugRepository
import dev.streampack.core.model.UserPrincipal
import java.time.Instant
import org.springframework.data.domain.Page
import org.springframework.stereotype.Component

/** A page of posts as the admin lists show them: the review queue and scheduled posts (#207). */
@Component
class AdminPostSummaries(
    private val slugRepository: SlugRepository,
    private val postTagRepository: PostTagRepository,
    private val postCategoryRepository: PostCategoryRepository,
    private val postVersionService: PostVersionService,
) {
    fun listing(page: Page<Post>, viewer: UserPrincipal?): ContentListResponse {
        // One query for the page's kinds of version (#200).
        val kinds = postVersionService.kindsFor(page.content.map { it.id })
        val now = Instant.now()
        val summaries =
            page.content.map { post ->
                val canonicalSlug = slugRepository.findCanonical(post.id)
                ContentSummary(
                    id = post.id,
                    title = post.title,
                    slug = canonicalSlug?.path ?: "",
                    excerpt = post.excerpt,
                    authorId = post.author?.id,
                    authorDisplayName = post.author?.displayName ?: "Anonymous",
                    authorRole = post.author?.role,
                    publishedAt = post.publishedAt,
                    tags =
                        PostTagVisibility.forViewer(
                            postTagRepository.findNamesByPost(post.id),
                            post,
                            viewer,
                        ),
                    categories = postCategoryRepository.findNamesByPost(post.id),
                    versionKinds = kinds[post.id].orEmpty(),
                    state = PostState.of(post.status, post.publishedAt, now),
                )
            }
        return ContentListResponse(
            posts = summaries,
            page = page.number,
            totalPages = page.totalPages,
            totalCount = page.totalElements,
        )
    }
}
