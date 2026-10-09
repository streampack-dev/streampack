/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.model

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

/** An admin's rating of an item (#187): `RATES`, `MIGHT` or `DULL`. */
data class RssItemRatingRequest(val rating: RssRating)

/** An item's rating as set (#187). Admin-only. */
data class RssItemRatingResponse(
    val itemId: UUID,
    val rating: RssRating,
    val ratedBy: String,
    val ratedAt: Instant,
)

/**
 * A stored item as `/rss/items` shows it, with its rating (#187), for the admin list. Never the
 * model's guess: that is kept from wherever items are rated, so it can't bias the ratings.
 */
data class AdminRssItemResponse(
    val id: UUID,
    val feedTitle: String,
    val feedUrl: String,
    val siteUrl: String?,
    val guid: String,
    val link: String,
    val title: String,
    val summary: String?,
    val publishedAt: Instant?,
    val receivedAt: Instant,
    val categories: List<String>,
    val tags: List<String>,
    @field:Schema(description = "The item's rating; null when it has none") val rating: RssRating?,
    @field:Schema(description = "The admin who rated it; null when it has no rating")
    val ratedBy: String?,
    @field:Schema(description = "When it was rated; null when it has no rating")
    val ratedAt: Instant?,
)

/** A page of the admin item list (#187). */
data class AdminRssItemsResponse(
    val items: List<AdminRssItemResponse>,
    val page: Int,
    val totalPages: Int,
    val totalCount: Long,
)

/** How many items carry each rating, and how many the model has guessed (#187). */
data class RssRatingCounts(
    val rates: Long,
    val might: Long,
    val dull: Long,
    @field:Schema(description = "Items with any rating") val rated: Long,
    @field:Schema(description = "Items with a guess from the model") val guessed: Long,
    @field:Schema(description = "Items with both a rating and a guess") val compared: Long,
)

/**
 * How the guess did on one rating (#187), over the items with both. [recall] is the share of items
 * rated this way that were guessed this way; [precision] the share guessed this way that were rated
 * this way. Each is null when there is nothing to divide by.
 */
data class RssRatingClassAgreement(
    val rating: RssRating,
    val rated: Long,
    val guessed: Long,
    val agreed: Long,
    val recall: Double?,
    val precision: Double?,
)

/** One cell of the confusion matrix: items rated [rated] that were guessed [guessed]. */
data class RssRatingConfusionCell(val rated: RssRating, val guessed: RssRating, val count: Long)

/** An item the guess got badly wrong (#187). */
data class RssRatingStatsItem(val itemId: UUID, val title: String, val feedTitle: String)

/** How the model's hidden guess agrees with admins' ratings (#187). Admin-only. */
data class RssRatingStatsResponse(
    val counts: RssRatingCounts,
    @field:Schema(
        description =
            "The share of compared items whose guess matches the rating; null when none are " +
                "compared"
    )
    val agreement: Double?,
    val perClass: List<RssRatingClassAgreement>,
    @field:Schema(description = "All nine cells, rated by guessed, zeros included")
    val confusion: List<RssRatingConfusionCell>,
    @field:Schema(description = "Guessed RATES, rated DULL")
    val guessedRatesRatedDull: List<RssRatingStatsItem>,
    @field:Schema(description = "Rated RATES, guessed DULL")
    val ratedRatesGuessedDull: List<RssRatingStatsItem>,
)

/** What a guess pass did (#187). */
data class RssRatingGuessRunResponse(
    @field:Schema(description = "Items received in the lookback with no guess, sent to the model")
    val candidates: Int,
    @field:Schema(description = "Calls made to the model, one per chunk") val calls: Int,
    @field:Schema(description = "Guesses stored") val stored: Int,
    @field:Schema(description = "Items left without a guess: a bad, partial or missing answer")
    val skipped: Int,
)
