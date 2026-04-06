/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.repository

import dev.streampack.blog.entity.Slug
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

/** Resolves URL paths to posts and manages slug aliases */
interface SlugRepository : JpaRepository<Slug, UUID> {
    @Query("SELECT s FROM Slug s WHERE s.path = :path") fun resolve(path: String): Slug?

    @Query("SELECT s FROM Slug s WHERE s.post.id = :postId")
    fun findByPost(postId: UUID): List<Slug>

    @Query("SELECT s FROM Slug s WHERE s.post.id = :postId AND s.canonical = true")
    fun findCanonical(postId: UUID): Slug?
}
