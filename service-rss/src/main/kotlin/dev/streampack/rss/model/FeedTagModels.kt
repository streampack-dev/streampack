/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.model

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

/** What became of a feed tag the vocabulary didn't know (#139). */
enum class FeedTagStatus {
    /** Counted, not yet a tag: it hasn't been on enough entries across enough feeds. */
    WAITING,

    /** It earned a place: created as a tag through the vocabulary's create rule. */
    PROMOTED,

    /** An admin created it as a tag. */
    CREATED,

    /** An admin mapped it to an existing tag (an alias), or it became a tag or alias elsewhere. */
    MAPPED,

    /** An admin ignored it (stoplisted), or it was stoplisted elsewhere. */
    IGNORED,
}

@Schema(description = "A feed entry carrying a feed tag, as an example")
data class FeedTagExample(
    val entryId: UUID,
    val title: String,
    val link: String,
    val feedTitle: String,
)

@Schema(description = "A feed tag the tag vocabulary didn't know, and what became of it")
data class FeedTagEntry(
    @field:Schema(description = "The feed tag, normalized") val name: String,
    @field:Schema(description = "How feeds wrote it, a few of the forms") val written: List<String>,
    val status: FeedTagStatus,
    @field:Schema(description = "Feed entries carrying it") val entries: Int,
    @field:Schema(description = "Distinct feeds carrying it") val feeds: Int,
    val firstSeen: Instant,
    val lastSeen: Instant,
    @field:Schema(description = "The tag it became or maps to; absent while waiting or ignored")
    val tag: String? = null,
    @field:Schema(description = "Who decided: an admin, rss (promoted), or vocabulary")
    val decidedBy: String? = null,
    val decidedAt: Instant? = null,
    @field:Schema(description = "A few of the entries carrying it, newest first")
    val examples: List<FeedTagExample>,
)

@Schema(description = "Feed tags, a page of them")
data class FeedTagListResponse(
    val tags: List<FeedTagEntry>,
    @field:Schema(description = "Waiting feed tags in all, for a launcher badge")
    val waitingCount: Long,
    @field:Schema(description = "Entries across feeds a waiting tag needs to become a tag")
    val promoteEntries: Int,
    @field:Schema(description = "Distinct feeds a waiting tag needs to become a tag")
    val promoteFeeds: Int,
    @field:Schema(description = "Feed tags in all for the status asked for, across every page")
    val totalCount: Long? = null,
    @field:Schema(description = "Pages in all for the status and size asked for; 0 when none")
    val totalPages: Int? = null,
)

@Schema(description = "Map a feed tag to an existing tag")
data class FeedTagMapRequest(
    @field:Schema(description = "The feed tag") val name: String = "",
    @field:Schema(description = "The existing tag it means") val tag: String = "",
)

@Schema(description = "A feed tag to ignore or create")
data class FeedTagRequest(@field:Schema(description = "The feed tag") val name: String = "")
