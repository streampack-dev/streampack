/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.repository

import dev.streampack.blog.entity.Post
import java.time.Instant
import java.util.UUID
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional

/** Queries for blog post retrieval by visibility state */
interface PostRepository : JpaRepository<Post, UUID> {
    @Query(
        "SELECT p FROM Post p LEFT JOIN FETCH p.author WHERE p.status = dev.streampack.blog.model.PostStatus.APPROVED AND p.deleted = false AND p.publishedAt <= :now ORDER BY FUNCTION('date_trunc', 'day', p.publishedAt) DESC, p.sortOrder ASC, p.publishedAt DESC"
    )
    fun findPublished(now: Instant): List<Post>

    @Query(
        "SELECT p FROM Post p WHERE p.status = dev.streampack.blog.model.PostStatus.DRAFT AND p.deleted = false"
    )
    fun findDrafts(): List<Post>

    @Query(
        "SELECT p FROM Post p WHERE p.status = dev.streampack.blog.model.PostStatus.APPROVED AND p.deleted = false AND p.publishedAt > :now ORDER BY p.publishedAt ASC"
    )
    fun findScheduled(now: Instant): List<Post>

    @Query("SELECT p FROM Post p WHERE p.author.id = :authorId AND p.deleted = false")
    fun findByAuthor(authorId: UUID): List<Post>

    @Query("SELECT p FROM Post p WHERE p.id = :id AND p.deleted = false")
    fun findActiveById(id: UUID): Post?

    /** Candidate posts whose markdown mentions the supplied term, for exact follow-up matching. */
    @Query(
        "SELECT p FROM Post p WHERE p.deleted = false AND LOWER(p.markdownSource) LIKE LOWER(CONCAT('%', :term, '%'))"
    )
    fun findCandidatesByMarkdownContaining(term: String): List<Post>

    @Query(
        "SELECT p FROM Post p LEFT JOIN FETCH p.author WHERE p.title = :title AND p.deleted = false"
    )
    fun findByTitleWithAuthor(title: String): Post?

    /** Published posts with eagerly loaded authors for RSS feed generation */
    @Query(
        "SELECT p FROM Post p LEFT JOIN FETCH p.author WHERE p.status = dev.streampack.blog.model.PostStatus.APPROVED AND p.deleted = false AND p.publishedAt <= :now ORDER BY FUNCTION('date_trunc', 'day', p.publishedAt) DESC, p.sortOrder ASC, p.publishedAt DESC"
    )
    fun findRecentPublishedWithAuthor(now: Instant, pageable: Pageable): List<Post>

    /** Paginated published posts for listing pages, excluding system categories */
    @Query(
        "SELECT p FROM Post p LEFT JOIN FETCH p.author WHERE p.status = dev.streampack.blog.model.PostStatus.APPROVED AND p.deleted = false AND p.publishedAt <= :now AND NOT EXISTS (SELECT pc FROM PostCategory pc WHERE pc.post = p AND pc.category.name LIKE '\\_%' ESCAPE '\\') ORDER BY FUNCTION('date_trunc', 'day', p.publishedAt) DESC, p.sortOrder ASC, p.publishedAt DESC",
        countQuery =
            "SELECT COUNT(p) FROM Post p WHERE p.status = dev.streampack.blog.model.PostStatus.APPROVED AND p.deleted = false AND p.publishedAt <= :now AND NOT EXISTS (SELECT pc FROM PostCategory pc WHERE pc.post = p AND pc.category.name LIKE '\\_%' ESCAPE '\\')",
    )
    fun findPublished(now: Instant, pageable: Pageable): Page<Post>

    /** Posts in a specific category, ordered by sortOrder then publishedAt */
    @Query(
        "SELECT p FROM Post p LEFT JOIN FETCH p.author WHERE p.status = dev.streampack.blog.model.PostStatus.APPROVED AND p.deleted = false AND p.publishedAt <= :now AND EXISTS (SELECT pc FROM PostCategory pc WHERE pc.post = p AND pc.category.name = :categoryName) ORDER BY FUNCTION('date_trunc', 'day', p.publishedAt) DESC, p.sortOrder ASC, p.publishedAt DESC",
        countQuery =
            "SELECT COUNT(p) FROM Post p WHERE p.status = dev.streampack.blog.model.PostStatus.APPROVED AND p.deleted = false AND p.publishedAt <= :now AND EXISTS (SELECT pc FROM PostCategory pc WHERE pc.post = p AND pc.category.name = :categoryName)",
    )
    fun findByCategory(categoryName: String, now: Instant, pageable: Pageable): Page<Post>

    /** Posts with a specific tag, excluding system-category content */
    @Query(
        "SELECT p FROM Post p LEFT JOIN FETCH p.author WHERE p.status = dev.streampack.blog.model.PostStatus.APPROVED AND p.deleted = false AND p.publishedAt <= :now AND EXISTS (SELECT pt FROM PostTag pt WHERE pt.post = p AND LOWER(pt.tag.name) = LOWER(:tagName)) AND NOT EXISTS (SELECT pc FROM PostCategory pc WHERE pc.post = p AND pc.category.name LIKE '\\_%' ESCAPE '\\') ORDER BY FUNCTION('date_trunc', 'day', p.publishedAt) DESC, p.sortOrder ASC, p.publishedAt DESC",
        countQuery =
            "SELECT COUNT(p) FROM Post p WHERE p.status = dev.streampack.blog.model.PostStatus.APPROVED AND p.deleted = false AND p.publishedAt <= :now AND EXISTS (SELECT pt FROM PostTag pt WHERE pt.post = p AND LOWER(pt.tag.name) = LOWER(:tagName)) AND NOT EXISTS (SELECT pc FROM PostCategory pc WHERE pc.post = p AND pc.category.name LIKE '\\_%' ESCAPE '\\')",
    )
    fun findByTag(tagName: String, now: Instant, pageable: Pageable): Page<Post>

    /** Single published post by slug in a specific category */
    @Query(
        "SELECT p FROM Post p LEFT JOIN FETCH p.author WHERE p.status = dev.streampack.blog.model.PostStatus.APPROVED AND p.deleted = false AND p.publishedAt <= :now AND EXISTS (SELECT s FROM Slug s WHERE s.post = p AND s.path = :slugPath) AND EXISTS (SELECT pc FROM PostCategory pc WHERE pc.post = p AND pc.category.name = :categoryName)"
    )
    fun findByCategoryAndSlug(categoryName: String, slugPath: String, now: Instant): Post?

    /** Single published post by slug in any system category (name starts with _) */
    @Query(
        "SELECT p FROM Post p LEFT JOIN FETCH p.author WHERE p.status = dev.streampack.blog.model.PostStatus.APPROVED AND p.deleted = false AND p.publishedAt <= :now AND EXISTS (SELECT s FROM Slug s WHERE s.post = p AND s.path = :slugPath) AND EXISTS (SELECT pc FROM PostCategory pc WHERE pc.post = p AND pc.category.name LIKE '\\_%' ESCAPE '\\')"
    )
    fun findBySystemCategoryAndSlug(slugPath: String, now: Instant): Post?

    /** Fetch post with author eagerly loaded to avoid LazyInitializationException in DTO mapping */
    @Query("SELECT p FROM Post p LEFT JOIN FETCH p.author WHERE p.id = :id AND p.deleted = false")
    fun findActiveByIdWithAuthor(id: UUID): Post?

    /** Hard-deletes a post by ID, bypassing Hibernate cascade checks (DB cascades handle FKs) */
    @Transactional
    @Modifying
    @Query("DELETE FROM Post p WHERE p.id = :id")
    fun hardDeleteById(id: UUID)

    /** Paginated draft posts for the admin review queue */
    @Query(
        "SELECT p FROM Post p LEFT JOIN FETCH p.author WHERE p.status = dev.streampack.blog.model.PostStatus.DRAFT AND p.deleted = false ORDER BY p.createdAt DESC",
        countQuery =
            "SELECT COUNT(p) FROM Post p WHERE p.status = dev.streampack.blog.model.PostStatus.DRAFT AND p.deleted = false",
    )
    fun findDrafts(pageable: Pageable): Page<Post>

    /** Paginated soft-deleted drafts for admin review/purge */
    @Query(
        "SELECT p FROM Post p LEFT JOIN FETCH p.author WHERE p.status = dev.streampack.blog.model.PostStatus.DRAFT AND p.deleted = true ORDER BY p.updatedAt DESC",
        countQuery =
            "SELECT COUNT(p) FROM Post p WHERE p.status = dev.streampack.blog.model.PostStatus.DRAFT AND p.deleted = true",
    )
    fun findDeletedDrafts(pageable: Pageable): Page<Post>

    /** Hard-deletes all posts by a given author (for purging erased user content) */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM Post p WHERE p.author.id = :authorId")
    fun hardDeleteByAuthor(authorId: UUID)

    /** Reassigns all posts from one author to another (for account erasure) */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Post p SET p.author.id = :toUserId WHERE p.author.id = :fromUserId")
    fun reassignAuthor(fromUserId: UUID, toUserId: UUID)

    /** Full-text search on published posts, ranked by relevance */
    @Query(
        nativeQuery = true,
        value =
            "SELECT p.* FROM posts p WHERE p.search_vector @@ plainto_tsquery('english', :query) AND p.status = 'APPROVED' AND p.deleted = FALSE AND p.published_at <= :now ORDER BY ts_rank(p.search_vector, plainto_tsquery('english', :query)) DESC",
        countQuery =
            "SELECT count(*) FROM posts p WHERE p.search_vector @@ plainto_tsquery('english', :query) AND p.status = 'APPROVED' AND p.deleted = FALSE AND p.published_at <= :now",
    )
    fun searchPublished(query: String, now: Instant, pageable: Pageable): Page<Post>
}
