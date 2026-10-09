/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.service

import com.rometools.rome.feed.synd.SyndEntry
import dev.streampack.rss.config.RssProperties
import dev.streampack.rss.entity.RssEntry
import dev.streampack.rss.entity.RssEntryCategory
import dev.streampack.rss.entity.RssFeedTag
import dev.streampack.rss.model.FeedTagEntry
import dev.streampack.rss.model.FeedTagExample
import dev.streampack.rss.model.FeedTagListResponse
import dev.streampack.rss.model.FeedTagStatus
import dev.streampack.rss.repository.RssEntryCategoryRepository
import dev.streampack.rss.repository.RssEntryRepository
import dev.streampack.rss.repository.RssFeedTagRepository
import dev.streampack.taxonomy.TagCuration
import dev.streampack.taxonomy.TagNames
import dev.streampack.taxonomy.TagVocabulary
import dev.streampack.taxonomy.model.TagResolution
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** A feed entry's own tags as written ([categories]) and the BCN tags they map to ([tags]). */
data class EntryTags(val categories: List<String>, val tags: List<String>)

/**
 * Feed entries' own tags, mapped onto BCN's (#139).
 *
 * Each poll, and a feed's registration, keeps every entry's categories ([FeedCategories]) in step
 * with the feed while the entry is in its window ([record]). A feed tag then maps through the tag
 * vocabulary ([TagVocabulary.resolveAll]), at read time: a tag or an alias maps to the tag, a
 * stoplisted term is ignored, and a system tag (`_idea`) is never taken from a feed. So the alias
 * and stoplist tables remember those decisions.
 *
 * Anything else waits, in `rss_feed_tag`, counted: it never becomes a tag just by appearing. Once
 * it's on [RssProperties.Tags.promoteEntries] entries across [RssProperties.Tags.promoteFeeds]
 * feeds it's created through the vocabulary's create rule ([TagVocabulary.accept]), so it gets the
 * usual review-queue hints and the AI near-miss, after the poll commits; and it's marked PROMOTED.
 * An admin can decide one by hand first: [map] it to a tag (an alias), [ignore] it (stoplisted) or
 * [create] it.
 *
 * Feed entries never count toward tag counts or the taxonomy: a promoted tag is a `tags` row that
 * no post or factoid carries until one does. Nothing here messages anyone.
 */
@Service
class FeedTagService(
    private val categories: RssEntryCategoryRepository,
    private val feedTags: RssFeedTagRepository,
    private val entries: RssEntryRepository,
    private val vocabulary: TagVocabulary,
    private val curation: TagCuration,
    private val properties: RssProperties,
) {
    private val log = LoggerFactory.getLogger(FeedTagService::class.java)

    /** A feed tag asked for isn't one we've seen. */
    class NotFoundException(message: String) : RuntimeException(message)

    /**
     * Keeps each stored entry's categories in step with the feed's [window] (each entry with what
     * the feed now says of it), then counts and, past the threshold, promotes the feed tags in it
     * that the vocabulary doesn't know. Runs in the poll's transaction.
     */
    @Transactional
    fun record(window: List<Pair<RssEntry, SyndEntry>>, now: Instant = Instant.now()) {
        if (window.isEmpty()) return
        val wanted = window.associate { (entry, synd) -> entry.id to FeedCategories.of(synd) }
        val stored = categories.findByEntryIdIn(wanted.keys).groupBy { it.entryId }
        for ((entryId, cats) in wanted) {
            val have = stored[entryId].orEmpty()
            val names = cats.map { it.name }.toSet()
            val gone = have.filter { it.name !in names }
            if (gone.isNotEmpty()) categories.deleteAll(gone)
            val haveNames = have.map { it.name }.toSet()
            val added =
                cats
                    .filter { it.name !in haveNames }
                    .map { RssEntryCategory(entryId = entryId, name = it.name, raw = it.raw) }
            if (added.isNotEmpty()) categories.saveAll(added)
        }
        categories.flush() // the counts are SQL
        settle(wanted.values.flatten().map { it.name }.toSet(), now)
    }

    /**
     * Counts each of [names] and settles it: a waiting feed tag that's now a tag, an alias or
     * stoplisted is marked decided by the vocabulary; one the vocabulary still doesn't know is
     * counted, and promoted past the threshold.
     */
    private fun settle(names: Set<String>, now: Instant) {
        val resolved = vocabulary.resolveAll(names.filterNot(TagNames::isSystem))
        if (resolved.isEmpty()) return
        val counts = categories.countByName(resolved.keys).associateBy { it.name }
        val rows = feedTags.findAllById(resolved.keys).associateBy { it.name }
        val threshold = properties.tags
        for ((name, resolution) in resolved) {
            val count = counts[name]
            val entryCount = count?.entries?.toInt() ?: 0
            val feedCount = count?.feeds?.toInt() ?: 0
            val row = rows[name]
            if (resolution !is TagResolution.New) {
                row ?: continue // decided by the vocabulary's own tables
                feedTags.save(
                    decidedElsewhere(row, resolution, now)
                        .copy(entries = entryCount, feeds = feedCount, lastSeen = now)
                )
                continue
            }
            var waiting =
                (row ?: RssFeedTag(name = name, firstSeen = now, lastSeen = now)).let {
                    // An alias or stop since removed: the admin's decision is undone, so it waits
                    if (it.status == FeedTagStatus.MAPPED || it.status == FeedTagStatus.IGNORED)
                        it.copy(
                            status = FeedTagStatus.WAITING,
                            tag = null,
                            decidedBy = null,
                            decidedAt = null,
                        )
                    else it
                }
            waiting = waiting.copy(entries = entryCount, feeds = feedCount, lastSeen = now)
            if (
                waiting.status == FeedTagStatus.WAITING &&
                    entryCount >= threshold.promoteEntries &&
                    feedCount >= threshold.promoteFeeds
            ) {
                val tag = vocabulary.accept(name, SOURCE)
                if (tag != null) {
                    waiting =
                        waiting.copy(
                            status = FeedTagStatus.PROMOTED,
                            tag = tag,
                            decidedBy = SOURCE,
                            decidedAt = now,
                        )
                    log.info(
                        "Feed tag '{}' promoted to a tag: {} entries across {} feeds",
                        name,
                        entryCount,
                        feedCount,
                    )
                }
            }
            feedTags.save(waiting)
        }
    }

    /** [row] marked as the vocabulary decided it, when it was still waiting. */
    private fun decidedElsewhere(
        row: RssFeedTag,
        resolution: TagResolution,
        now: Instant,
    ): RssFeedTag {
        if (row.status != FeedTagStatus.WAITING) return row
        val status =
            if (resolution is TagResolution.Stopped) FeedTagStatus.IGNORED else FeedTagStatus.MAPPED
        return row.copy(
            status = status,
            tag = resolution.name,
            decidedBy = VOCABULARY,
            decidedAt = now,
        )
    }

    /**
     * Each of [entryIds]' categories as written, and the BCN tags they map to, both sorted: a tag
     * or an alias's tag; never a stoplisted, system or waiting one. Resolved as of now, so an alias
     * made or a tag promoted since the entry was stored applies to it.
     */
    @Transactional(readOnly = true)
    fun tagsFor(entryIds: Collection<UUID>): Map<UUID, EntryTags> {
        if (entryIds.isEmpty()) return emptyMap()
        val byEntry = categories.findByEntryIdIn(entryIds).groupBy { it.entryId }
        val resolved = vocabulary.resolveAll(byEntry.values.flatten().map { it.name }.toSet())
        return entryIds.associateWith { id ->
            val cats = byEntry[id].orEmpty()
            EntryTags(
                categories = cats.map { it.raw }.sorted(),
                tags = cats.mapNotNull { mapped(resolved[it.name]) }.distinct().sorted(),
            )
        }
    }

    private fun mapped(resolution: TagResolution?): String? =
        when (resolution) {
            is TagResolution.Canonical -> resolution.name
            is TagResolution.Aliased -> resolution.name
            else -> null
        }

    /**
     * A page of feed tags in [status] (all when null), the most carried first, with how many there
     * are in [status] and how many pages of [size] that makes. A waiting one that the vocabulary
     * has decided since it was last seen is shown, and stored, as decided; the totals are counted
     * after that.
     */
    @Transactional
    fun list(status: FeedTagStatus?, page: Int, size: Int): FeedTagListResponse {
        val rows = feedTags.findPage(status, PageRequest.of(page, size))
        val waiting = rows.filter { it.status == FeedTagStatus.WAITING }.map { it.name }
        val resolved = vocabulary.resolveAll(waiting)
        val now = Instant.now()
        val settled = rows.map { row ->
            val resolution = resolved[row.name]
            if (
                row.status == FeedTagStatus.WAITING &&
                    resolution != null &&
                    resolution !is TagResolution.New
            ) {
                feedTags.save(decidedElsewhere(row, resolution, now))
            } else row
        }
        val total = if (status == null) feedTags.count() else feedTags.countByStatus(status)
        return FeedTagListResponse(
            tags = settled.map(::entry),
            waitingCount = feedTags.countByStatus(FeedTagStatus.WAITING),
            totalCount = total,
            totalPages = TagCuration.pages(total, size),
            promoteEntries = properties.tags.promoteEntries,
            promoteFeeds = properties.tags.promoteFeeds,
        )
    }

    /** The feed tag [raw] names, as [entry] shows it. */
    @Transactional(readOnly = true) fun get(raw: String): FeedTagEntry = entry(row(raw))

    /**
     * Maps the feed tag [raw] to the existing tag [target]: it becomes an alias of it (as `tag
     * alias` makes one), taken off the stoplist first if it was there. Marked MAPPED.
     */
    @Transactional
    fun map(raw: String, target: String, actor: String): FeedTagEntry {
        val row = row(raw)
        if (vocabulary.resolve(row.name) is TagResolution.Stopped) {
            curation.unstop(row.name, actor)
        }
        val result = curation.alias(row.name, target, actor)
        return entry(feedTags.save(decided(row, FeedTagStatus.MAPPED, result.now.first(), actor)))
    }

    /**
     * Ignores the feed tag [raw]: it's stoplisted (its alias removed first if it had one), so it's
     * dropped wherever it's written from now on. Marked IGNORED.
     */
    @Transactional
    fun ignore(raw: String, actor: String): FeedTagEntry {
        val row = row(raw)
        if (vocabulary.resolve(row.name) is TagResolution.Aliased) {
            curation.removeAlias(row.name, actor)
        }
        curation.stop(row.name, actor)
        return entry(feedTags.save(decided(row, FeedTagStatus.IGNORED, null, actor)))
    }

    /**
     * Creates the feed tag [raw] as a tag now, through the vocabulary's create rule (so it's hinted
     * in the review queue and checked by the AI near-miss as any new tag is), taking it off the
     * stoplist or out of the aliases first. Marked CREATED.
     */
    @Transactional
    fun create(raw: String, actor: String): FeedTagEntry {
        val row = row(raw)
        when (vocabulary.resolve(row.name)) {
            is TagResolution.Stopped -> curation.unstop(row.name, actor)
            is TagResolution.Aliased -> curation.removeAlias(row.name, actor)
            else -> {}
        }
        val tag =
            vocabulary.accept(row.name, SOURCE)
                ?: throw IllegalArgumentException("'${row.name}' can't be a tag")
        return entry(feedTags.save(decided(row, FeedTagStatus.CREATED, tag, actor)))
    }

    private fun decided(row: RssFeedTag, status: FeedTagStatus, tag: String?, actor: String) =
        row.copy(status = status, tag = tag, decidedBy = actor, decidedAt = Instant.now())

    private fun row(raw: String): RssFeedTag {
        val name = TagNames.normalize(raw) ?: throw IllegalArgumentException("No feed tag given")
        return feedTags.findById(name).orElse(null)
            ?: throw NotFoundException("No feed tag '$name' has been seen")
    }

    private fun entry(row: RssFeedTag): FeedTagEntry {
        val ids = categories.findExampleEntryIds(row.name, EXAMPLES)
        val byId = entries.findAllById(ids).associateBy { it.id }
        return FeedTagEntry(
            name = row.name,
            written = categories.findWritten(row.name, EXAMPLES),
            status = row.status,
            entries = row.entries,
            feeds = row.feeds,
            firstSeen = row.firstSeen,
            lastSeen = row.lastSeen,
            tag = row.tag,
            decidedBy = row.decidedBy,
            decidedAt = row.decidedAt,
            examples =
                ids.mapNotNull { byId[it] }
                    .map { FeedTagExample(it.id, it.title, it.link, it.feed.title) },
        )
    }

    companion object {
        /** What feed tags are created as, in the review queue and the logs. */
        const val SOURCE = "rss"

        /** Who decided a feed tag the vocabulary's own tables decided. */
        const val VOCABULARY = "vocabulary"

        /** How many example entries and written forms a feed tag shows. */
        const val EXAMPLES = 3
    }
}
