/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.service

import dev.streampack.rss.entity.RssEntry
import dev.streampack.rss.entity.RssItemRating
import dev.streampack.rss.entity.RssItemRatingHistory
import dev.streampack.rss.model.AdminRssItemResponse
import dev.streampack.rss.model.AdminRssItemsResponse
import dev.streampack.rss.model.RssAggregatedItemResponse
import dev.streampack.rss.model.RssItemRatingResponse
import dev.streampack.rss.model.RssRating
import dev.streampack.rss.model.RssRatingClassAgreement
import dev.streampack.rss.model.RssRatingConfusionCell
import dev.streampack.rss.model.RssRatingCounts
import dev.streampack.rss.model.RssRatingStatsItem
import dev.streampack.rss.model.RssRatingStatsResponse
import dev.streampack.rss.repository.RssEntryRepository
import dev.streampack.rss.repository.RssItemGuessRepository
import dev.streampack.rss.repository.RssItemRatingHistoryRepository
import dev.streampack.rss.repository.RssItemRatingRepository
import java.time.Instant
import java.util.UUID
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Admins' ratings of feed items (#187): set, cleared, listed, exported, and measured against the
 * model's hidden guess. Every method here is for admins; nothing public reads ratings or guesses.
 */
@Service
class RssRatingService(
    private val entries: RssEntryRepository,
    private val ratings: RssItemRatingRepository,
    private val history: RssItemRatingHistoryRepository,
    private val guesses: RssItemGuessRepository,
    private val aggregator: RssAggregatorService,
) {
    class NotFoundException(message: String) : RuntimeException(message)

    /** Which items the admin list shows: one rating, the unrated ones, or all. */
    sealed interface Filter {
        data class Rated(val rating: RssRating) : Filter

        data object Unrated : Filter

        data object All : Filter

        companion object {
            /**
             * `rates`, `might`, `dull`, `unrated` or `all`, in any case; null for anything else.
             */
            fun parse(value: String): Filter? =
                when (value.trim().lowercase()) {
                    "all" -> All
                    "unrated" -> Unrated
                    else -> RssRating.parse(value)?.let(::Rated)
                }
        }
    }

    /**
     * Rates item [itemId] [rating], replacing any rating it had; the change is kept in the history.
     * The same rating again changes nothing.
     */
    @Transactional
    fun rate(
        itemId: UUID,
        rating: RssRating,
        admin: String,
        now: Instant = Instant.now(),
    ): RssItemRatingResponse {
        requireItem(itemId)
        val current = ratings.findById(itemId).orElse(null)
        if (current?.rating == rating) return current.toResponse()
        // Read before the save, which may update the very instance found
        val previous = current?.rating
        val saved =
            ratings.save(
                RssItemRating(itemId = itemId, rating = rating, ratedBy = admin, ratedAt = now)
            )
        history.save(
            RssItemRatingHistory(
                itemId = itemId,
                rating = rating,
                previous = previous,
                actedBy = admin,
                actedAt = now,
            )
        )
        return saved.toResponse()
    }

    /** Clears item [itemId]'s rating, kept in the history; true if it had one. */
    @Transactional
    fun clear(itemId: UUID, admin: String, now: Instant = Instant.now()): Boolean {
        requireItem(itemId)
        val current = ratings.findById(itemId).orElse(null) ?: return false
        ratings.delete(current)
        history.save(
            RssItemRatingHistory(
                itemId = itemId,
                rating = null,
                previous = current.rating,
                actedBy = admin,
                actedAt = now,
            )
        )
        return true
    }

    /** Item [itemId]'s changes of rating, oldest first. */
    fun historyOf(itemId: UUID): List<RssItemRatingHistory> =
        history.findByItemIdOrderByActedAtAscIdAsc(itemId)

    /**
     * A page of stored items as `/rss/items` lists them, narrowed by [filter], each with its
     * rating. Never with the model's guess.
     */
    @Transactional(readOnly = true)
    fun list(
        filter: Filter,
        page: Int,
        size: Int,
        feed: String?,
        title: String?,
    ): AdminRssItemsResponse {
        val listed = aggregator.listItems(page, size, feed, title, specificationFor(filter))
        val rated = ratings.findAllById(listed.items.map { it.id }).associateBy { it.itemId }
        return AdminRssItemsResponse(
            items = listed.items.map { it.withRating(rated[it.id]) },
            page = listed.page,
            totalPages = listed.totalPages,
            totalCount = listed.totalCount,
        )
    }

    /**
     * Every item with a rating or a guess, as CSV (RFC 4180, CRLF line ends), newest rating first,
     * then the guessed-only ones newest first. The guess columns are empty when there's no guess,
     * and the rating ones when there's no rating. A text field that a spreadsheet would take for a
     * formula (starting `=`, `+`, `-`, `@`, tab or CR) is prefixed with `'`.
     */
    @Transactional(readOnly = true)
    fun exportCsv(): String {
        val rated = ratings.findAll().associateBy { it.itemId }
        val guessed = guesses.findAll().associateBy { it.itemId }
        val items = entries.findAllById(rated.keys + guessed.keys).associateBy { it.id }
        val order =
            rated.values.sortedByDescending { it.ratedAt }.map { it.itemId } +
                guessed.values
                    .filter { it.itemId !in rated }
                    .sortedByDescending { it.guessedAt }
                    .map { it.itemId }
        val out = StringBuilder()
        out.append(CSV_HEADER.joinToString(",")).append("\r\n")
        for (id in order) {
            val entry = items[id] ?: continue
            val rating = rated[id]
            val guess = guessed[id]
            val row =
                listOf(
                    id.toString(),
                    text(entry.feed.title),
                    text(entry.title),
                    text(entry.link),
                    entry.publishedAt?.toString().orEmpty(),
                    rating?.rating?.name.orEmpty(),
                    text(rating?.ratedBy.orEmpty()),
                    rating?.ratedAt?.toString().orEmpty(),
                    guess?.label?.name.orEmpty(),
                    guess?.confidence?.toString().orEmpty(),
                    text(guess?.reason.orEmpty()),
                    text(guess?.model.orEmpty()),
                    guess?.textSource?.name?.lowercase().orEmpty(),
                    guess?.guessedAt?.toString().orEmpty(),
                )
            out.append(row.joinToString(",") { quote(it) }).append("\r\n")
        }
        return out.toString()
    }

    /**
     * Counts per rating, and how the guess agrees with the ratings over the items that have both:
     * overall, per rating, as a confusion matrix, and the two worst kinds of miss.
     */
    @Transactional(readOnly = true)
    fun stats(): RssRatingStatsResponse {
        val rated = ratings.findAll().associate { it.itemId to it.rating }
        val guessed = guesses.findAll().associateBy { it.itemId }
        val pairs = rated.mapNotNull { (id, rating) -> guessed[id]?.let { id to (rating to it) } }
        val compared = pairs.size.toLong()
        val agreed = pairs.count { (_, p) -> p.first == p.second.label }.toLong()
        val perClass =
            RssRating.entries.map { r ->
                val ratedAs = pairs.count { (_, p) -> p.first == r }.toLong()
                val guessedAs = pairs.count { (_, p) -> p.second.label == r }.toLong()
                val both = pairs.count { (_, p) -> p.first == r && p.second.label == r }.toLong()
                RssRatingClassAgreement(
                    rating = r,
                    rated = ratedAs,
                    guessed = guessedAs,
                    agreed = both,
                    recall = share(both, ratedAs),
                    precision = share(both, guessedAs),
                )
            }
        val confusion =
            RssRating.entries.flatMap { r ->
                RssRating.entries.map { g ->
                    RssRatingConfusionCell(
                        rated = r,
                        guessed = g,
                        count =
                            pairs.count { (_, p) -> p.first == r && p.second.label == g }.toLong(),
                    )
                }
            }
        val misses: (RssRating, RssRating) -> List<UUID> = { r, g ->
            pairs.filter { (_, p) -> p.first == r && p.second.label == g }.map { it.first }
        }
        val falseRates = misses(RssRating.DULL, RssRating.RATES)
        val missedRates = misses(RssRating.RATES, RssRating.DULL)
        val items = entries.findAllById(falseRates + missedRates).associateBy { it.id }
        fun listed(ids: List<UUID>) =
            ids.mapNotNull { items[it] }
                .sortedByDescending { it.createdAt }
                .map { RssRatingStatsItem(it.id, it.title, it.feed.title) }
        return RssRatingStatsResponse(
            counts =
                RssRatingCounts(
                    rates = rated.values.count { it == RssRating.RATES }.toLong(),
                    might = rated.values.count { it == RssRating.MIGHT }.toLong(),
                    dull = rated.values.count { it == RssRating.DULL }.toLong(),
                    rated = rated.size.toLong(),
                    guessed = guessed.size.toLong(),
                    compared = compared,
                ),
            agreement = share(agreed, compared),
            perClass = perClass,
            confusion = confusion,
            guessedRatesRatedDull = listed(falseRates),
            ratedRatesGuessedDull = listed(missedRates),
        )
    }

    private fun requireItem(itemId: UUID) {
        if (!entries.existsById(itemId)) throw NotFoundException("No feed item $itemId")
    }

    private fun specificationFor(filter: Filter): Specification<RssEntry>? =
        when (filter) {
            Filter.All -> null
            Filter.Unrated ->
                Specification { root, query, cb -> cb.not(cb.exists(rated(root, query, cb, null))) }
            is Filter.Rated ->
                Specification { root, query, cb ->
                    cb.exists(rated(root, query, cb, filter.rating))
                }
        }

    /** The ratings of the item at [root], of [rating] only when it's given. */
    private fun rated(
        root: jakarta.persistence.criteria.Root<RssEntry>,
        query: jakarta.persistence.criteria.CriteriaQuery<*>,
        cb: jakarta.persistence.criteria.CriteriaBuilder,
        rating: RssRating?,
    ): jakarta.persistence.criteria.Subquery<UUID> {
        val sub = query.subquery(UUID::class.java)
        val r = sub.from(RssItemRating::class.java)
        val same = cb.equal(r.get<UUID>("itemId"), root.get<UUID>("id"))
        sub.select(r.get("itemId"))
        sub.where(
            if (rating == null) same else cb.and(same, cb.equal(r.get<RssRating>("rating"), rating))
        )
        return sub
    }

    private fun RssItemRating.toResponse() = RssItemRatingResponse(itemId, rating, ratedBy, ratedAt)

    private fun RssAggregatedItemResponse.withRating(rating: RssItemRating?) =
        AdminRssItemResponse(
            id = id,
            feedTitle = feedTitle,
            feedUrl = feedUrl,
            siteUrl = siteUrl,
            guid = guid,
            link = link,
            title = title,
            summary = summary,
            publishedAt = publishedAt,
            receivedAt = receivedAt,
            categories = categories,
            tags = tags,
            rating = rating?.rating,
            ratedBy = rating?.ratedBy,
            ratedAt = rating?.ratedAt,
        )

    companion object {
        val CSV_HEADER =
            listOf(
                "item_id",
                "feed",
                "title",
                "link",
                "published",
                "rating",
                "rated_by",
                "rated_at",
                "guess",
                "guess_confidence",
                "guess_reason",
                "guess_model",
                "guess_source",
                "guessed_at",
            )

        private fun share(part: Long, whole: Long): Double? =
            if (whole == 0L) null else part.toDouble() / whole

        /** [value] made safe from a spreadsheet's formulas. */
        private fun text(value: String): String =
            if (value.isNotEmpty() && value[0] in "=+-@\t\r") "'$value" else value

        /** [value] quoted when it holds a comma, quote or line break (RFC 4180). */
        private fun quote(value: String): String =
            if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
                "\"" + value.replace("\"", "\"\"") + "\""
            else value
    }
}
