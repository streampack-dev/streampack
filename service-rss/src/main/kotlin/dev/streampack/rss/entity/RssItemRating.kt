/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.entity

import dev.streampack.rss.model.RssRating
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.UuidGenerator

/** An admin's rating of a feed item (#187), one per item. Admin-only: never public. */
@Entity
@Table(name = "rss_item_rating")
data class RssItemRating(
    @Id val itemId: UUID = UUID(0, 0),
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    val rating: RssRating = RssRating.MIGHT,
    @Column(nullable = false) val ratedBy: String = "",
    @Column(nullable = false) val ratedAt: Instant = Instant.now(),
)

/**
 * A change to an item's rating: [rating] is what it became (null when cleared), [previous] what it
 * was (null when it had none).
 */
@Entity
@Table(name = "rss_item_rating_history")
data class RssItemRatingHistory(
    @Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7) val id: UUID = UUID(0, 0),
    @Column(nullable = false) val itemId: UUID = UUID(0, 0),
    @Enumerated(EnumType.STRING) @Column(length = 10) val rating: RssRating? = null,
    @Enumerated(EnumType.STRING) @Column(length = 10) val previous: RssRating? = null,
    @Column(nullable = false) val actedBy: String = "",
    @Column(nullable = false) val actedAt: Instant = Instant.now(),
)

/**
 * The model's hidden guess at an item's rating (#187). Never shown where items are rated: only the
 * CSV export and the rating stats read it.
 */
@Entity
@Table(name = "rss_item_guess")
data class RssItemGuess(
    @Id val itemId: UUID = UUID(0, 0),
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    val label: RssRating = RssRating.MIGHT,
    @Column(nullable = false) val confidence: Double = 0.0,
    @Column(nullable = false, length = 1000) val reason: String = "",
    val model: String? = null,
    /** What the model judged the item on: its feed content, its page, or its summary. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    val textSource: RssTextSource = RssTextSource.SUMMARY,
    @Column(nullable = false) val guessedAt: Instant = Instant.now(),
)

/**
 * Where an item's text came from (#187): the feed's own [CONTENT], the article [PAGE], its
 * [SUMMARY], or only its [TITLE]. [NONE] marks a page fetch that found no text, so it isn't fetched
 * again; a guess never records it.
 */
enum class RssTextSource {
    CONTENT,
    PAGE,
    SUMMARY,
    TITLE,
    NONE,
}

/** An item's full text, kept for the guess (#187), never public. */
@Entity
@Table(name = "rss_entry_text")
data class RssEntryText(
    @Id val entryId: UUID = UUID(0, 0),
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    val source: RssTextSource = RssTextSource.NONE,
    @Column(columnDefinition = "TEXT") val content: String? = null,
    @Column(nullable = false) val storedAt: Instant = Instant.now(),
)
