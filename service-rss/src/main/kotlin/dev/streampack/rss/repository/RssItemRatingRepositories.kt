/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.repository

import dev.streampack.rss.entity.RssEntryText
import dev.streampack.rss.entity.RssItemGuess
import dev.streampack.rss.entity.RssItemRating
import dev.streampack.rss.entity.RssItemRatingHistory
import dev.streampack.rss.entity.RssTextSource
import dev.streampack.rss.model.RssRating
import java.time.Instant
import java.util.UUID
import org.springframework.data.domain.Page
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

    /**
     * A page of guesses with their items and the editor's ratings (#192), the newest guess first.
     * Every filter is off when blank or [anyLabel]: [label] only when not [anyLabel]; [rated] is
     * `all`, `yes` or `no`; [feed] matches a feed's title or address, lowercased; [title] is a
     * lowercased `like` pattern. Scalars only, so it needs no session to read.
     */
    @Query(
        value =
            """
            select new dev.streampack.rss.repository.RssItemGuessRow(
                e.id, e.title, e.link, f.title, g.label, g.confidence, g.reason, g.textSource,
                g.model, g.rubric, g.guessedAt, r.rating)
            from RssItemGuess g
            join RssEntry e on e.id = g.itemId
            join e.feed f
            left join RssItemRating r on r.itemId = g.itemId
            where (:anyLabel = true or g.label = :label)
              and (:rated = 'all'
                   or (:rated = 'yes' and r.itemId is not null)
                   or (:rated = 'no' and r.itemId is null))
              and (:feed = '' or lower(f.title) = :feed or lower(f.feedUrl) = :feed)
              and (:title = '' or lower(e.title) like :title)
            order by g.guessedAt desc, g.itemId desc
            """,
        countQuery =
            """
            select count(g)
            from RssItemGuess g
            join RssEntry e on e.id = g.itemId
            join e.feed f
            left join RssItemRating r on r.itemId = g.itemId
            where (:anyLabel = true or g.label = :label)
              and (:rated = 'all'
                   or (:rated = 'yes' and r.itemId is not null)
                   or (:rated = 'no' and r.itemId is null))
              and (:feed = '' or lower(f.title) = :feed or lower(f.feedUrl) = :feed)
              and (:title = '' or lower(e.title) like :title)
            """,
    )
    fun findListing(
        @Param("anyLabel") anyLabel: Boolean,
        @Param("label") label: RssRating,
        @Param("rated") rated: String,
        @Param("feed") feed: String,
        @Param("title") title: String,
        pageable: Pageable,
    ): Page<RssItemGuessRow>
}

/** A guess as the admin list of guesses reads it (#192): the item, the guess, the rating. */
data class RssItemGuessRow(
    val itemId: UUID,
    val title: String,
    val link: String,
    val feedTitle: String,
    val label: RssRating,
    val confidence: Double,
    val reason: String,
    val textSource: RssTextSource,
    val model: String?,
    val rubric: String?,
    val guessedAt: Instant,
    val rating: RssRating?,
)

/** Items' full text, for the guess (#187). */
interface RssEntryTextRepository : JpaRepository<RssEntryText, UUID>
