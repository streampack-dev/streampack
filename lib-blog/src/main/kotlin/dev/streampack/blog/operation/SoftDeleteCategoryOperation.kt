/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.model.ContentOperationConfirmation
import dev.streampack.blog.model.SoftDeleteCategoryRequest
import dev.streampack.blog.repository.CategoryRepository
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Role
import dev.streampack.core.service.TypedOperation
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Admin soft-deletes a category, hiding it from selection */
@Component
class SoftDeleteCategoryOperation(private val categoryRepository: CategoryRepository) :
    TypedOperation<SoftDeleteCategoryRequest>(SoftDeleteCategoryRequest::class) {

    override val priority = 50

    override fun handle(payload: SoftDeleteCategoryRequest, message: Message<*>): OperationOutcome {
        requireRole(message, Role.ADMIN)?.let {
            return it
        }

        val category =
            categoryRepository.findById(payload.id).orElse(null)
                ?: return OperationResult.Error("Category not found")

        if (category.deleted) {
            return OperationResult.Error("Category is already deleted")
        }

        categoryRepository.save(category.copy(deleted = true))

        logger.info("Category soft-deleted: {}", category.id)

        return OperationResult.Success(
            ContentOperationConfirmation(id = category.id, message = "Category deleted")
        )
    }
}
