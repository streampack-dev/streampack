/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.entity.Category
import dev.streampack.blog.entity.Post
import dev.streampack.blog.entity.PostCategory
import dev.streampack.blog.entity.PostTag
import dev.streampack.blog.entity.Slug
import dev.streampack.blog.entity.Tag
import dev.streampack.blog.model.CreateContentRequest
import dev.streampack.blog.model.CreateContentResponse
import dev.streampack.blog.model.PostStatus
import dev.streampack.blog.repository.CategoryRepository
import dev.streampack.blog.repository.PostCategoryRepository
import dev.streampack.blog.repository.PostRepository
import dev.streampack.blog.repository.PostTagRepository
import dev.streampack.blog.repository.SlugRepository
import dev.streampack.blog.repository.TagRepository
import dev.streampack.blog.service.BlogNotificationService
import dev.streampack.blog.service.MarkdownRenderingService
import dev.streampack.blog.service.SlugGenerationService
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.TypedOperation
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Creates a new blog post draft from a markdown submission */
@Component
class CreateContentOperation(
    private val postRepository: PostRepository,
    private val slugRepository: SlugRepository,
    private val userRepository: UserRepository,
    private val markdownRenderingService: MarkdownRenderingService,
    private val slugGenerationService: SlugGenerationService,
    private val tagRepository: TagRepository,
    private val postTagRepository: PostTagRepository,
    private val categoryRepository: CategoryRepository,
    private val postCategoryRepository: PostCategoryRepository,
    private val blogNotificationService: BlogNotificationService,
) : TypedOperation<CreateContentRequest>(CreateContentRequest::class) {

    override fun handle(payload: CreateContentRequest, message: Message<*>): OperationOutcome {
        val provenance =
            message.headers[Provenance.HEADER] as? Provenance
                ?: return OperationResult.Error("No provenance context")

        val principal = provenance.user
        val user =
            if (principal != null) {
                userRepository.findActiveById(principal.id)
                    ?: return OperationResult.Error("User not found")
            } else {
                null
            }

        if (user != null && !user.emailVerified) {
            return OperationResult.Error("Email verification required")
        }

        if (payload.title.isBlank()) {
            return OperationResult.Error("Title is required")
        }
        if (payload.markdownSource.isBlank()) {
            return OperationResult.Error("Content is required")
        }

        val renderedHtml = markdownRenderingService.render(payload.markdownSource)
        val providedSummary = payload.summary?.trim().orEmpty()
        val excerpt =
            if (providedSummary.isNotBlank()) {
                providedSummary
            } else {
                markdownRenderingService.excerpt(payload.markdownSource).ifBlank {
                    payload.title.trim()
                }
            }
        val now = java.time.Instant.now()

        val post =
            postRepository.save(
                Post(
                    title = payload.title,
                    markdownSource = payload.markdownSource,
                    renderedHtml = renderedHtml,
                    excerpt = excerpt,
                    status = PostStatus.DRAFT,
                    author = user,
                    createdAt = now,
                    updatedAt = now,
                )
            )

        val tagNames = assignTags(post, payload.tags ?: emptyList())
        val resolvedCategories = resolveCategories(payload.categoryIds ?: emptyList())
        resolvedCategories.forEach { category ->
            postCategoryRepository.save(PostCategory(post = post, category = category))
        }
        val categoryNames = resolvedCategories.map { it.name }

        val isSystemCategory = categoryNames.any { it.startsWith("_") }
        val slugPath =
            if (isSystemCategory) {
                slugGenerationService.generateBareSlug(payload.title)
            } else {
                slugGenerationService.generateSlug(payload.title, now)
            }
        slugRepository.save(Slug(path = slugPath, post = post, canonical = true))

        blogNotificationService.notifyPostSubmission(post, slugPath, user)

        logger.info("Post created: {} with slug {}", post.id, slugPath)

        return OperationResult.Success(
            CreateContentResponse(
                id = post.id,
                title = post.title,
                slug = slugPath,
                excerpt = post.excerpt,
                status = post.status,
                authorId = user?.id,
                authorDisplayName = user?.displayName ?: "Anonymous",
                createdAt = post.createdAt,
                tags = tagNames,
                categories = categoryNames,
            )
        )
    }

    /** Resolves or creates tags by name and associates them with the post */
    private fun assignTags(post: Post, tagNames: List<String>): List<String> {
        val resolved =
            tagNames
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .distinct()
                .map { name ->
                    tagRepository.findByName(name)
                        ?: tagRepository.save(
                            Tag(name = name, slug = slugGenerationService.slugify(name))
                        )
                }
        resolved.forEach { tag -> postTagRepository.save(PostTag(post = post, tag = tag)) }
        return resolved.map { it.name }
    }

    /** Resolves category IDs to entities, skipping missing or deleted ones */
    private fun resolveCategories(categoryIds: List<java.util.UUID>): List<Category> {
        return categoryIds.distinct().mapNotNull { id ->
            categoryRepository.findById(id).orElse(null)?.takeIf { !it.deleted }
        }
    }
}
