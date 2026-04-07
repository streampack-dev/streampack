/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.repository

import dev.streampack.blog.entity.PostTag
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional

/** Manages post-to-tag assignments */
interface PostTagRepository : JpaRepository<PostTag, UUID> {
    @Query("SELECT pt FROM PostTag pt WHERE pt.post.id = :postId")
    fun findByPost(postId: UUID): List<PostTag>

    @Query("SELECT pt.tag.name FROM PostTag pt WHERE pt.post.id = :postId ORDER BY pt.tag.name")
    fun findNamesByPost(postId: UUID): List<String>

    @Query("SELECT pt FROM PostTag pt WHERE pt.tag.id = :tagId")
    fun findByTag(tagId: UUID): List<PostTag>

    @Modifying
    @Transactional
    @Query("DELETE FROM PostTag pt WHERE pt.post.id = :postId")
    fun deleteByPost(postId: UUID)

    @Query(
        """
        SELECT LOWER(pt.tag.name) AS name, COUNT(pt.id) AS count
        FROM PostTag pt
        WHERE pt.tag.deleted = false
          AND pt.post.deleted = false
          AND SUBSTRING(LOWER(pt.tag.name), 1, 1) <> '_'
        GROUP BY LOWER(pt.tag.name)
        ORDER BY COUNT(pt.id) DESC, LOWER(pt.tag.name) ASC
        """
    )
    fun findTagCounts(): List<NameCountProjection>
}

interface NameCountProjection {
    val name: String
    val count: Long
}
