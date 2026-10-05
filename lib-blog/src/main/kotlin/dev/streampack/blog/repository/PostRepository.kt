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

    /**
     * Live posts whose outgoing links haven't been handled since they last changed (#112, #128):
     * published (approved, not deleted, publishedAt past), and `metadata.linksCheckedThrough`
     * (epoch millis) short of `updatedAt`. Oldest first.
     *
     * Not indexable as it stands: it compares two columns of a row, and the epoch of a timestamptz
     * isn't immutable, so it can't go in an index or a partial index's predicate. At a blog's size
     * (hundreds to thousands of posts) the scan is a millisecond or so, and Postgres would scan a
     * table that small anyway. If posts ever run to tens of thousands: keep `links_checked_through`
     * as a timestamptz column rather than in metadata, add a stored generated column `links_due =
     * links_checked_through IS NULL OR links_checked_through < updated_at` (immutable, so allowed),
     * and a partial index on `published_at WHERE links_due` over published posts.
     */
    @Query(
        nativeQuery = true,
        value =
            "SELECT * FROM posts WHERE status = 'APPROVED' AND deleted = false AND published_at <= :now " +
                "AND COALESCE((metadata->>'linksCheckedThrough')::bigint, 0) < " +
                "FLOOR(EXTRACT(EPOCH FROM updated_at) * 1000)::bigint " +
                "ORDER BY published_at ASC LIMIT :limit",
    )
    fun findOutgoingLinksDue(now: Instant, limit: Int): List<Post>

    /**
     * Merges [patch] (a JSON object) into a post's metadata, key by key, touching nothing else: not
     * updatedAt, and not an edit made meanwhile.
     */
    @Transactional
    @Modifying
    @Query(
        nativeQuery = true,
        value = "UPDATE posts SET metadata = metadata || CAST(:patch AS jsonb) WHERE id = :id",
    )
    fun mergeMetadata(id: UUID, patch: String): Int

    /**
     * Drafts carrying [tagName] (the article ideas' `_idea`), oldest first, with their authors
     * loaded, so a caller outside a transaction can read them.
     */
    @Query(
        "SELECT p FROM Post p LEFT JOIN FETCH p.author WHERE p.status = dev.streampack.blog.model.PostStatus.DRAFT AND p.deleted = false AND EXISTS (SELECT pt FROM PostTag pt WHERE pt.post = p AND pt.tag.name = :tagName) ORDER BY p.createdAt ASC"
    )
    fun findDraftsTaggedWithAuthor(tagName: String): List<Post>

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

    /**
     * Full-text search on published posts, ranked by relevance over title (A), tags and excerpt (B)
     * and body text (C). Queries read like a web search box: quoted phrases, `or`, `-term`. Visible
     * posts only, by the same rule as the board and tag listings: approved, published, not deleted,
     * not in a hidden (`_`) category.
     */
    @Query(
        nativeQuery = true,
        value =
            "SELECT p.* FROM posts p WHERE p.search_vector @@ websearch_to_tsquery('english', :query) AND p.status = 'APPROVED' AND p.deleted = FALSE AND p.published_at <= :now AND NOT EXISTS (SELECT 1 FROM post_categories pc JOIN categories c ON c.id = pc.category_id WHERE pc.post_id = p.id AND c.name LIKE '\\_%' ESCAPE '\\') ORDER BY ts_rank(p.search_vector, websearch_to_tsquery('english', :query)) DESC",
        countQuery =
            "SELECT count(*) FROM posts p WHERE p.search_vector @@ websearch_to_tsquery('english', :query) AND p.status = 'APPROVED' AND p.deleted = FALSE AND p.published_at <= :now AND NOT EXISTS (SELECT 1 FROM post_categories pc JOIN categories c ON c.id = pc.category_id WHERE pc.post_id = p.id AND c.name LIKE '\\_%' ESCAPE '\\')",
    )
    fun searchPublished(query: String, now: Instant, pageable: Pageable): Page<Post>
}
