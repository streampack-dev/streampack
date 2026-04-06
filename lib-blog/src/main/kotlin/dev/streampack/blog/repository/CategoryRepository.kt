/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.repository

import dev.streampack.blog.entity.Category
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

/** Queries for hierarchical content categories */
interface CategoryRepository : JpaRepository<Category, UUID> {
    @Query("SELECT c FROM Category c WHERE c.deleted = false") fun findActive(): List<Category>

    @Query("SELECT c FROM Category c WHERE c.parent IS NULL AND c.deleted = false")
    fun findRoots(): List<Category>

    @Query("SELECT c FROM Category c WHERE c.parent.id = :parentId AND c.deleted = false")
    fun findChildren(parentId: UUID): List<Category>

    fun findBySlug(slug: String): Category?

    fun findByName(name: String): Category?
}
