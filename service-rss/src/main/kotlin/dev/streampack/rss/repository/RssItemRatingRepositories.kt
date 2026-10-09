/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.repository

import dev.streampack.rss.entity.RssEntryText
import dev.streampack.rss.entity.RssItemGuess
import dev.streampack.rss.entity.RssItemRating
import dev.streampack.rss.entity.RssItemRatingHistory
import dev.streampack.rss.model.RssRating
import java.time.Instant
import java.util.UUID
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/** Admins' ratings of feed items (#187). */
interface RssItemRatingRepository : JpaRepository<RssItemRating, UUID> {
    /** The most recent ratings of [rating], newest first: the guess's few-shot examples. */
    fun findByRatingOrderByRatedAtDesc(rating: RssRating, pageable: Pageable): List<RssItemRating>
}

/** Every change to an item's rating (#187). */
interface RssItemRatingHistoryRepository : JpaRepository<RssItemRatingHistory, UUID> {
    fun findByItemIdOrderByActedAtAscIdAsc(itemId: UUID): List<RssItemRatingHistory>
}

/** The model's hidden guesses (#187). */
interface RssItemGuessRepository : JpaRepository<RssItemGuess, UUID> {
    /**
     * Items received since [since] that have no guess yet, the oldest first, at most [limit]: what
     * a guess pass sends.
     */
    @Query(
        value =
            """
            SELECT e.id FROM rss_entries e
            WHERE e.created_at >= :since
              AND NOT EXISTS (SELECT 1 FROM rss_item_guess g WHERE g.item_id = e.id)
            ORDER BY e.created_at, e.id
            LIMIT :limit
            """,
        nativeQuery = true,
    )
    fun findUnguessedSince(@Param("since") since: Instant, @Param("limit") limit: Int): List<UUID>
}

/** Items' full text, for the guess (#187). */
interface RssEntryTextRepository : JpaRepository<RssEntryText, UUID>
