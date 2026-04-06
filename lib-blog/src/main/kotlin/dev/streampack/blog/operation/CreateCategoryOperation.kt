/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.entity.Category
import dev.streampack.blog.model.CreateCategoryRequest
import dev.streampack.blog.model.CreateCategoryResponse
import dev.streampack.blog.repository.CategoryRepository
import dev.streampack.blog.service.SlugGenerationService
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Role
import dev.streampack.core.service.TypedOperation
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Admin creates a new content category */
@Component
class CreateCategoryOperation(
    private val categoryRepository: CategoryRepository,
    private val slugGenerationService: SlugGenerationService,
) : TypedOperation<CreateCategoryRequest>(CreateCategoryRequest::class) {

    override val priority = 50

    override fun handle(payload: CreateCategoryRequest, message: Message<*>): OperationOutcome {
        requireRole(message, Role.ADMIN)?.let {
            return it
        }

        val name = payload.name.trim()
        if (name.isBlank()) {
            return OperationResult.Error("Category name is required")
        }

        val existing = categoryRepository.findByName(name)
        if (existing != null) {
            return OperationResult.Error("Category name already exists")
        }

        val parent =
            if (payload.parentId != null) {
                val p =
                    categoryRepository.findById(payload.parentId).orElse(null)
                        ?: return OperationResult.Error("Parent category not found")
                if (p.deleted) {
                    return OperationResult.Error("Parent category not found")
                }
                p
            } else {
                null
            }

        val slug = slugGenerationService.slugify(name)
        val category = categoryRepository.save(Category(name = name, slug = slug, parent = parent))

        logger.info("Category created: {} ({})", category.name, category.id)

        return OperationResult.Success(
            CreateCategoryResponse(
                id = category.id,
                name = category.name,
                slug = category.slug,
                parentName = parent?.name,
            )
        )
    }
}
