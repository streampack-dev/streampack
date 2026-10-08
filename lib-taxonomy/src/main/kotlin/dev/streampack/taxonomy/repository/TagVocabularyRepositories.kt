/* Joseph B. Ottinger (C)2026 */
package dev.streampack.taxonomy.repository

import dev.streampack.taxonomy.entity.TagAction
import dev.streampack.taxonomy.entity.TagAlias
import dev.streampack.taxonomy.entity.TagReview
import dev.streampack.taxonomy.entity.TagStop
import dev.streampack.taxonomy.model.TagReviewStatus
import java.util.UUID
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

/** Tag aliases, by alias. */
interface TagAliasRepository : JpaRepository<TagAlias, String> {
    @Query("SELECT a FROM TagAlias a JOIN FETCH a.tag ORDER BY a.alias")
    fun findAllWithTag(): List<TagAlias>

    @Query("SELECT a FROM TagAlias a WHERE a.tag.id = :tagId")
    fun findByTag(tagId: UUID): List<TagAlias>
}

/** The tag stoplist, by term. */
interface TagStopRepository : JpaRepository<TagStop, String> {
    fun findAllByOrderByTermAsc(): List<TagStop>
}

/** The tag review queue. */
interface TagReviewRepository : JpaRepository<TagReview, UUID> {
    fun findByTag(tag: String): TagReview?

    /**
     * Entries in [status] (all when null): the AI's most confident first, then newest first, so the
     * likeliest near-misses lead.
     */
    @Query(
        """
        SELECT r FROM TagReview r
        WHERE (:status IS NULL OR r.status = :status)
        ORDER BY CASE WHEN r.aiConfidence IS NULL THEN 1 ELSE 0 END, r.aiConfidence DESC,
          r.firstSeen DESC
        """
    )
    fun findQueue(status: TagReviewStatus?, pageable: Pageable): List<TagReview>

    fun countByStatus(status: TagReviewStatus): Long
}

/** The log of vocabulary changes. */
interface TagActionRepository : JpaRepository<TagAction, UUID> {
    fun findAllByOrderByActedAtDesc(pageable: Pageable): List<TagAction>
}
