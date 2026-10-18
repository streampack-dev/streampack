/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.repository

import dev.streampack.blog.entity.PostVersion
import java.time.Instant
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

/**
 * A post's versions (#196). Each query fetches what its callers read, as no session stays open past
 * the repository call.
 */
interface PostVersionRepository : JpaRepository<PostVersion, UUID> {
    /** Every version of a post, with its maker. */
    @Query(
        "SELECT v FROM PostVersion v LEFT JOIN FETCH v.maker WHERE v.post.id = :postId " +
            "ORDER BY v.kind, v.variant",
    )
    fun findByPost(postId: UUID): List<PostVersion>

    /** A post's versions of one kind, with the post, for serving. */
    @Query(
        "SELECT v FROM PostVersion v JOIN FETCH v.post WHERE v.post.id = :postId AND v.kind = :kind",
    )
    fun findByPostAndKind(postId: UUID, kind: String): List<PostVersion>

    /** The versions of one kind for several posts, with their posts. */
    @Query(
        "SELECT v FROM PostVersion v JOIN FETCH v.post WHERE v.post.id IN :postIds AND v.kind = :kind",
    )
    fun findByPostsAndKind(postIds: Collection<UUID>, kind: String): List<PostVersion>

    /** The kinds of version each of several posts has, a row per post and kind (#200). */
    @Query(
        "SELECT DISTINCT v.post.id AS postId, v.kind AS kind FROM PostVersion v " +
            "WHERE v.post.id IN :postIds",
    )
    fun findKindsByPosts(postIds: Collection<UUID>): List<PostVersionKindProjection>

    /**
     * Versions of [kind] without a variant whose posts anonymous readers can see (approved, not
     * deleted, published by [now], not system content), with their posts and the posts' authors,
     * newest post first.
     */
    @Query(
        "SELECT v FROM PostVersion v JOIN FETCH v.post p LEFT JOIN FETCH p.author " +
            "WHERE v.kind = :kind AND v.variant IS NULL " +
            "AND p.status = dev.streampack.blog.model.PostStatus.APPROVED AND p.deleted = false " +
            "AND p.publishedAt <= :now AND " +
            SystemContent.NOT_SYSTEM +
            " " +
            "ORDER BY p.publishedAt DESC",
    )
    fun findPublishedByKind(kind: String, now: Instant): List<PostVersion>

    /** The storage keys of the versions of every post by [authorId], read before a purge. */
    @Query("SELECT v.storageKey FROM PostVersion v WHERE v.post.author.id = :authorId")
    fun findStorageKeysByPostAuthor(authorId: UUID): List<String>
}

/** A post and one kind of version it has (#200). */
interface PostVersionKindProjection {
    val postId: UUID
    val kind: String
}
