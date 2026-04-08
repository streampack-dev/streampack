/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.repository

import dev.streampack.blog.entity.Comment
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional

/** Retrieves comments for thread display and user history */
interface CommentRepository : JpaRepository<Comment, UUID> {
    @Query("SELECT c FROM Comment c WHERE c.post.id = :postId ORDER BY c.createdAt ASC")
    fun findByPost(postId: UUID): List<Comment>

    @Query(
        "SELECT c FROM Comment c LEFT JOIN FETCH c.author WHERE c.post.id = :postId ORDER BY c.createdAt ASC"
    )
    fun findByPostWithAuthor(postId: UUID): List<Comment>

    @Query(
        "SELECT c FROM Comment c LEFT JOIN FETCH c.author WHERE c.id = :id AND c.deleted = false"
    )
    fun findActiveByIdWithAuthor(id: UUID): Comment?

    @Query(
        "SELECT c FROM Comment c WHERE c.author.id = :authorId AND c.deleted = false ORDER BY c.createdAt DESC"
    )
    fun findByAuthor(authorId: UUID): List<Comment>

    @Query("SELECT COUNT(c) FROM Comment c WHERE c.post.id = :postId AND c.deleted = false")
    fun countActiveByPost(postId: UUID): Long

    /** Hard-deletes all comments by a given author (for purging erased user content) */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM Comment c WHERE c.author.id = :authorId")
    fun hardDeleteByAuthor(authorId: UUID)

    /** Reassigns all comments from one author to another (for account erasure) */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Comment c SET c.author.id = :toUserId WHERE c.author.id = :fromUserId")
    fun reassignAuthor(fromUserId: UUID, toUserId: UUID)

    @Transactional
    @Modifying
    @Query("DELETE FROM Comment c WHERE c.id = :id")
    fun hardDeleteById(id: UUID)
}
