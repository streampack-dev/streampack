/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.repository

import dev.streampack.blog.entity.PostCategory
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional

/** Manages post-to-category assignments */
interface PostCategoryRepository : JpaRepository<PostCategory, UUID> {
    @Query("SELECT pc FROM PostCategory pc WHERE pc.post.id = :postId")
    fun findByPost(postId: UUID): List<PostCategory>

    @Query("SELECT pc FROM PostCategory pc WHERE pc.category.id = :categoryId")
    fun findByCategory(categoryId: UUID): List<PostCategory>

    @Modifying
    @Transactional
    @Query("DELETE FROM PostCategory pc WHERE pc.post.id = :postId")
    fun deleteByPost(postId: UUID)

    @Query(
        """
        SELECT LOWER(pc.category.name) AS name, COUNT(pc.id) AS count
        FROM PostCategory pc
        WHERE pc.category.deleted = false
          AND pc.post.deleted = false
          AND SUBSTRING(LOWER(pc.category.name), 1, 1) <> '_'
        GROUP BY LOWER(pc.category.name)
        ORDER BY COUNT(pc.id) DESC, LOWER(pc.category.name) ASC
        """
    )
    fun findCategoryCounts(): List<NameCountProjection>
}
