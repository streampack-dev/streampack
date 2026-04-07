/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.entity.Post
import dev.streampack.blog.model.ContentDetail
import dev.streampack.blog.model.ContentListResponse
import dev.streampack.blog.model.ContentSummary
import dev.streampack.blog.model.FindContentRequest
import dev.streampack.blog.model.PostStatus
import dev.streampack.blog.repository.CommentRepository
import dev.streampack.blog.repository.PostCategoryRepository
import dev.streampack.blog.repository.PostRepository
import dev.streampack.blog.repository.PostTagRepository
import dev.streampack.blog.repository.SlugRepository
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.service.TypedOperation
import java.time.Instant
import java.util.UUID
import org.springframework.data.domain.PageRequest
import org.springframework.messaging.Message
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** Retrieves blog content by slug, ID, or paginated published listing */
@Component
@Transactional(readOnly = true)
class FindContentOperation(
    private val postRepository: PostRepository,
    private val slugRepository: SlugRepository,
    private val commentRepository: CommentRepository,
    private val postTagRepository: PostTagRepository,
    private val postCategoryRepository: PostCategoryRepository,
) : TypedOperation<FindContentRequest>(FindContentRequest::class) {

    override val priority = 50

    override fun handle(payload: FindContentRequest, message: Message<*>): OperationOutcome {
        val provenance = message.headers[Provenance.HEADER] as? Provenance
        val user = provenance?.user

        return when (payload) {
            is FindContentRequest.FindBySlug -> findBySlug(payload.path, user)
            is FindContentRequest.FindById -> findById(payload.id, user)
            is FindContentRequest.FindPublished -> findPublished(payload.page, payload.size)
            is FindContentRequest.Search ->
                searchPublished(payload.query, payload.page, payload.size)
            is FindContentRequest.FindByCategory ->
                findByCategory(payload.categoryName, payload.page, payload.size)
            is FindContentRequest.FindByTag ->
                findByTag(payload.tagName, payload.page, payload.size)
            is FindContentRequest.FindPage -> findPage(payload.slug, user)
        }
    }

    private fun findBySlug(path: String, user: UserPrincipal?): OperationResult {
        val slug = slugRepository.resolve(path) ?: return OperationResult.Error("Post not found")

        val post =
            postRepository.findActiveByIdWithAuthor(slug.post.id)
                ?: return OperationResult.Error("Post not found")

        if (!isVisible(post, user)) {
            return OperationResult.Error("Post not found")
        }

        val canonicalSlug = slugRepository.findCanonical(post.id)
        return OperationResult.Success(toDetail(post, canonicalSlug?.path ?: path, user))
    }

    private fun findById(id: java.util.UUID, user: UserPrincipal?): OperationResult {
        val post =
            postRepository.findActiveByIdWithAuthor(id)
                ?: return OperationResult.Error("Post not found")

        if (!isVisible(post, user)) {
            return OperationResult.Error("Post not found")
        }

        val canonicalSlug = slugRepository.findCanonical(post.id)
        return OperationResult.Success(toDetail(post, canonicalSlug?.path ?: "", user))
    }

    private fun findPublished(page: Int, size: Int): OperationResult {
        val now = Instant.now()
        val pageResult = postRepository.findPublished(now, PageRequest.of(page, size))

        val summaries =
            pageResult.content.map { post ->
                val canonicalSlug = slugRepository.findCanonical(post.id)
                ContentSummary(
                    id = post.id,
                    title = post.title,
                    slug = canonicalSlug?.path ?: "",
                    excerpt = post.excerpt,
                    authorDisplayName = post.author?.displayName ?: "Anonymous",
                    publishedAt = post.publishedAt,
                    sortOrder = post.sortOrder,
                    commentCount = commentRepository.countActiveByPost(post.id).toInt(),
                    tags = tagNamesForPost(post.id),
                    categories = categoryNamesForPost(post.id),
                )
            }

        return OperationResult.Success(
            ContentListResponse(
                posts = summaries,
                page = pageResult.number,
                totalPages = pageResult.totalPages,
                totalCount = pageResult.totalElements,
            )
        )
    }

    private fun searchPublished(query: String, page: Int, size: Int): OperationResult {
        if (query.isBlank()) {
            return OperationResult.Success(
                ContentListResponse(posts = emptyList(), page = 0, totalPages = 0, totalCount = 0)
            )
        }

        val now = Instant.now()
        val pageResult = postRepository.searchPublished(query, now, PageRequest.of(page, size))

        val summaries =
            pageResult.content.map { post ->
                val canonicalSlug = slugRepository.findCanonical(post.id)
                ContentSummary(
                    id = post.id,
                    title = post.title,
                    slug = canonicalSlug?.path ?: "",
                    excerpt = post.excerpt,
                    authorDisplayName = post.author?.displayName ?: "Anonymous",
                    publishedAt = post.publishedAt,
                    sortOrder = post.sortOrder,
                    commentCount = commentRepository.countActiveByPost(post.id).toInt(),
                    tags = tagNamesForPost(post.id),
                    categories = categoryNamesForPost(post.id),
                )
            }

        return OperationResult.Success(
            ContentListResponse(
                posts = summaries,
                page = pageResult.number,
                totalPages = pageResult.totalPages,
                totalCount = pageResult.totalElements,
            )
        )
    }

    private fun findByCategory(categoryName: String, page: Int, size: Int): OperationResult {
        val now = Instant.now()
        val pageResult =
            postRepository.findByCategory(categoryName, now, PageRequest.of(page, size))

        val summaries =
            pageResult.content.map { post ->
                val canonicalSlug = slugRepository.findCanonical(post.id)
                ContentSummary(
                    id = post.id,
                    title = post.title,
                    slug = canonicalSlug?.path ?: "",
                    excerpt = post.excerpt,
                    authorDisplayName = post.author?.displayName ?: "Anonymous",
                    publishedAt = post.publishedAt,
                    sortOrder = post.sortOrder,
                    commentCount = commentRepository.countActiveByPost(post.id).toInt(),
                    tags = tagNamesForPost(post.id),
                    categories = categoryNamesForPost(post.id),
                )
            }

        return OperationResult.Success(
            ContentListResponse(
                posts = summaries,
                page = pageResult.number,
                totalPages = pageResult.totalPages,
                totalCount = pageResult.totalElements,
            )
        )
    }

    private fun findByTag(tagName: String, page: Int, size: Int): OperationResult {
        val now = Instant.now()
        val pageResult = postRepository.findByTag(tagName, now, PageRequest.of(page, size))

        val summaries =
            pageResult.content.map { post ->
                val canonicalSlug = slugRepository.findCanonical(post.id)
                ContentSummary(
                    id = post.id,
                    title = post.title,
                    slug = canonicalSlug?.path ?: "",
                    excerpt = post.excerpt,
                    authorDisplayName = post.author?.displayName ?: "Anonymous",
                    publishedAt = post.publishedAt,
                    sortOrder = post.sortOrder,
                    commentCount = commentRepository.countActiveByPost(post.id).toInt(),
                    tags = tagNamesForPost(post.id),
                    categories = categoryNamesForPost(post.id),
                )
            }

        return OperationResult.Success(
            ContentListResponse(
                posts = summaries,
                page = pageResult.number,
                totalPages = pageResult.totalPages,
                totalCount = pageResult.totalElements,
            )
        )
    }

    private fun findPage(slug: String, user: UserPrincipal?): OperationResult {
        val now = Instant.now()
        val post =
            postRepository.findBySystemCategoryAndSlug(slug, now)
                ?: return OperationResult.Error("Page not found")

        val canonicalSlug = slugRepository.findCanonical(post.id)
        return OperationResult.Success(toDetail(post, canonicalSlug?.path ?: slug, user))
    }

    /** Check visibility rules based on post state and requesting user */
    private fun isVisible(post: Post, user: UserPrincipal?): Boolean {
        val now = Instant.now()
        val isPublished =
            post.status == PostStatus.APPROVED &&
                post.publishedAt != null &&
                !post.publishedAt!!.isAfter(now)

        if (isPublished) return true

        // Unpublished content: only author or admin can see
        if (user == null) return false
        if (user.role == Role.ADMIN || user.role == Role.SUPER_ADMIN) return true
        if (post.author != null && post.author!!.id == user.id) return true

        return false
    }

    private fun toDetail(post: Post, slug: String, user: UserPrincipal? = null): ContentDetail {
        val canEdit = user != null && (user.role == Role.ADMIN || user.role == Role.SUPER_ADMIN)

        return ContentDetail(
            id = post.id,
            title = post.title,
            slug = slug,
            renderedHtml = post.renderedHtml,
            excerpt = post.excerpt,
            authorId = post.author?.id,
            authorDisplayName = post.author?.displayName ?: "Anonymous",
            status = post.status,
            publishedAt = post.publishedAt,
            sortOrder = post.sortOrder,
            createdAt = post.createdAt,
            updatedAt = post.updatedAt,
            commentCount = commentRepository.countActiveByPost(post.id).toInt(),
            tags = tagNamesForPost(post.id),
            categories = categoryNamesForPost(post.id),
            markdownSource = if (canEdit) post.markdownSource else null,
        )
    }

    private fun tagNamesForPost(postId: UUID): List<String> =
        postTagRepository.findNamesByPost(postId)

    private fun categoryNamesForPost(postId: UUID): List<String> =
        postCategoryRepository.findNamesByPost(postId)
}
