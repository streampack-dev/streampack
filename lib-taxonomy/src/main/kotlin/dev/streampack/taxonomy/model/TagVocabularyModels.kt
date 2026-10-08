/* Joseph B. Ottinger (C)2026 */
package dev.streampack.taxonomy.model

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

/**
 * What `TagVocabulary.resolve` makes of a tag being written (#140). Every case carries the [name]
 * to store, except [Stopped], which stores nothing.
 */
sealed interface TagResolution {
    /** The name to store, or null when the tag is dropped. */
    val name: String?

    /** An existing tag, as written. */
    data class Canonical(override val name: String) : TagResolution

    /** An alias, stored as the tag it means. */
    data class Aliased(val alias: String, override val name: String) : TagResolution

    /** A stoplisted term: dropped. */
    data class Stopped(val term: String) : TagResolution {
        override val name: String? = null
    }

    /** A system tag (`_idea`): passed through, never aliased, stopped or queued. */
    data class System(override val name: String) : TagResolution

    /**
     * A tag the vocabulary doesn't have yet: accepted as written and created. [hint] is why an
     * admin might want to look at it, or null when nothing about it looks doubtful.
     */
    data class New(override val name: String, val hint: TagHint?) : TagResolution
}

/** Why a new tag looks doubtful: [kind], and the existing tags it resembles or is made of. */
data class TagHint(val kind: TagHintKind, val tags: List<String>)

/** The kinds of doubt a new tag can raise. */
enum class TagHintKind {
    /** A trailing-`s` pair with an existing tag (`compiler` and `compilers`). */
    PLURAL,

    /** Several words, each an existing tag: a likely missing comma (`java kotlin`). */
    MISSING_COMMA,

    /** No rule matched, but the AI near-miss named an existing tag it may duplicate. */
    AI,
}

/** Where a review entry stands. */
enum class TagReviewStatus {
    OPEN,
    ALIASED,
    SPLIT,
    KEPT,
    DISMISSED,
}

/** The kinds of change made to the vocabulary, as `tag_action` records them. */
enum class TagActionType {
    ALIAS,
    UNALIAS,
    SPLIT,
    KEEP,
    DISMISS,
    STOP,
    UNSTOP,
}

@Schema(description = "A doubtful new tag in the admin review queue")
data class TagReviewEntry(
    val id: UUID,
    @field:Schema(description = "The tag as it was written and created") val tag: String,
    @field:Schema(description = "When it was first written") val firstSeen: Instant,
    @field:Schema(description = "What first wrote it: post, factoid") val source: String,
    @field:Schema(
        description =
            "PLURAL (a trailing-s pair with hintTags[0]), MISSING_COMMA (hintTags are the " +
                "existing tags it's made of) or AI (only the AI near-miss found something)"
    )
    val hintKind: TagHintKind,
    @field:Schema(description = "The existing tags the hint names") val hintTags: List<String>,
    @field:Schema(description = "The existing tag the AI thinks this duplicates")
    val aiCandidate: String? = null,
    @field:Schema(description = "The AI's confidence, 0 to 1") val aiConfidence: Double? = null,
    @field:Schema(description = "Why the AI thinks so") val aiReason: String? = null,
    @field:Schema(description = "The model that answered") val aiModel: String? = null,
    val status: TagReviewStatus,
    @field:Schema(description = "Who decided, once decided") val actedBy: String? = null,
    @field:Schema(description = "When it was decided") val actedAt: Instant? = null,
)

@Schema(description = "The review queue, a page of it")
data class TagReviewListResponse(
    val entries: List<TagReviewEntry>,
    @field:Schema(description = "Open entries in all, for a launcher badge") val openCount: Long,
)

@Schema(description = "An alias and the tag it means")
data class TagAliasEntry(
    val alias: String,
    val tag: String,
    val createdBy: String,
    val createdAt: Instant,
)

@Schema(description = "A stoplisted term")
data class TagStopEntry(val term: String, val createdBy: String, val createdAt: Instant)

@Schema(description = "One change to the tag vocabulary")
data class TagActionEntry(
    val action: TagActionType,
    @field:Schema(description = "The tag or term acted on") val subject: String,
    @field:Schema(description = "What it became, or other detail") val detail: String?,
    val actor: String,
    val actedAt: Instant,
)

@Schema(description = "What an alias, split, keep or dismiss did")
data class TagChangeResult(
    @field:Schema(description = "The tag acted on") val tag: String,
    @field:Schema(description = "What it now stands for: the alias target, or the split's parts")
    val now: List<String>,
    @field:Schema(description = "Posts re-pointed") val posts: Int,
    @field:Schema(description = "Factoids whose tag lists were rewritten") val factoids: Int,
)

@Schema(description = "Make a tag an alias of another")
data class TagAliasRequest(
    @field:Schema(description = "The name to alias; required when creating an alias")
    val alias: String? = null,
    @field:Schema(description = "The tag it means") val tag: String = "",
)

@Schema(description = "Split a tag into its parts")
data class TagSplitRequest(
    @field:Schema(description = "The parts; the entry's hint tags when left out")
    val parts: List<String>? = null
)

@Schema(description = "A term for the stoplist") data class TagStopRequest(val term: String = "")

/** Published when a write creates a tag the vocabulary didn't have, for the AI near-miss. */
data class NewTagEvent(val tag: String, val source: String)
