/* Joseph B. Ottinger (C)2026 */
package dev.streampack.taxonomy

import dev.streampack.taxonomy.entity.TagAction
import dev.streampack.taxonomy.entity.TagAlias
import dev.streampack.taxonomy.entity.TagReview
import dev.streampack.taxonomy.entity.TagStop
import dev.streampack.taxonomy.model.TagActionEntry
import dev.streampack.taxonomy.model.TagActionType
import dev.streampack.taxonomy.model.TagAliasEntry
import dev.streampack.taxonomy.model.TagChangeResult
import dev.streampack.taxonomy.model.TagHintKind
import dev.streampack.taxonomy.model.TagReviewEntry
import dev.streampack.taxonomy.model.TagReviewListResponse
import dev.streampack.taxonomy.model.TagReviewStatus
import dev.streampack.taxonomy.model.TagStopEntry
import dev.streampack.taxonomy.repository.TagActionRepository
import dev.streampack.taxonomy.repository.TagAliasRepository
import dev.streampack.taxonomy.repository.TagRepository
import dev.streampack.taxonomy.repository.TagReviewRepository
import dev.streampack.taxonomy.repository.TagStopRepository
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * What admins do with the tag vocabulary (#140): the review queue, aliases and the stoplist. Every
 * change is recorded in `tag_action` with who made it and when.
 *
 * An alias or a split re-points every use of the tag, posts and factoids alike ([TagUsages]), in
 * the one transaction, and removes the tag's own row: an alias then owns the name.
 *
 * Errors are [IllegalArgumentException] (a bad request) and [NotFoundException].
 */
@Service
class TagCuration(
    private val vocabulary: TagVocabulary,
    private val tags: TagRepository,
    private val aliases: TagAliasRepository,
    private val stops: TagStopRepository,
    private val reviews: TagReviewRepository,
    private val actions: TagActionRepository,
    private val usages: ObjectProvider<TagUsages>,
) {
    private val log = LoggerFactory.getLogger(TagCuration::class.java)

    /** Something asked for isn't there: a review entry, an alias or a stoplisted term. */
    class NotFoundException(message: String) : RuntimeException(message)

    /** The queue, or the entries in [status], the AI's likeliest near-misses first. */
    @Transactional(readOnly = true)
    fun queue(status: TagReviewStatus?, page: Int, size: Int): TagReviewListResponse =
        TagReviewListResponse(
            entries = reviews.findQueue(status, PageRequest.of(page, size)).map(::entry),
            openCount = reviews.countByStatus(TagReviewStatus.OPEN),
        )

    /** The review entry [id]'s tag. */
    @Transactional(readOnly = true)
    fun reviewTag(id: UUID): String =
        reviews.findById(id).orElse(null)?.tag
            ?: throw NotFoundException("Tag review entry not found")

    /**
     * Makes [raw] an alias of the tag [target] names: every post and factoid carrying it is
     * re-pointed to the target, its row goes, and its aliases move to the target. A queued entry
     * for it is marked ALIASED. Writing or looking up [raw] afterwards finds the target.
     */
    @Transactional
    fun alias(raw: String, target: String, actor: String): TagChangeResult {
        val names = sourceNames(raw)
        val to = existing(target)
        require(names.any { it != to }) { "'$raw' is already '$to'" }
        val key = TagNames.normalize(raw)!!
        require(!stops.existsById(to)) { "'$to' is stoplisted" }
        val toTag = vocabulary.ensureTag(to)
        tags.flush() // retagging is SQL, and needs the row
        val counts = retag(names - to, listOf(to), actor)
        for (name in names - to) {
            tags.findByName(name)?.let { old ->
                aliases.findByTag(old.id).forEach { aliases.save(it.copy(tag = toTag)) }
                aliases.flush()
                tags.delete(old)
                tags.flush()
            }
        }
        if (key != to) {
            aliases.save(TagAlias(alias = key, tag = toTag, createdBy = actor, createdAt = now()))
        }
        close(key, TagReviewStatus.ALIASED, actor)
        record(TagActionType.ALIAS, key, to, actor)
        log.info("Tag '{}' aliased to '{}' by {}: {}", key, to, actor, counts)
        return TagChangeResult(key, listOf(to), counts.first, counts.second)
    }

    /**
     * Splits [raw], a tag that's likely a missing comma, into [parts] (its review entry's hint tags
     * when null): every post and factoid carrying it carries the parts instead, and its row goes.
     * Its queued entry is marked SPLIT.
     */
    @Transactional
    fun split(raw: String, parts: List<String>?, actor: String): TagChangeResult {
        val key = TagNames.normalize(raw) ?: throw IllegalArgumentException("No tag given")
        val names = sourceNames(raw)
        val wanted =
            parts?.takeIf { it.isNotEmpty() }
                ?: reviews
                    .findByTag(key)
                    ?.takeIf { it.hintKind == TagHintKind.MISSING_COMMA }
                    ?.hintTags
                ?: throw IllegalArgumentException("Give the parts to split '$key' into")
        val into =
            wanted
                .map { part ->
                    val resolved =
                        vocabulary.resolve(part)?.name
                            ?: throw IllegalArgumentException("'$part' is not a tag")
                    require(!TagNames.isSystem(resolved)) { "'$part' is a system tag" }
                    resolved
                }
                .distinct()
        require(into.size >= 2) { "A split needs two parts or more; use an alias for one" }
        require(names.none { it in into }) { "'$key' can't be one of its own parts" }
        into.forEach { vocabulary.ensureTag(it) }
        tags.flush() // retagging is SQL, and needs the rows
        val counts = retag(names, into, actor)
        for (name in names) {
            tags.findByName(name)?.let { old ->
                tags.delete(old)
                tags.flush()
            }
        }
        close(key, TagReviewStatus.SPLIT, actor)
        record(TagActionType.SPLIT, key, into.joinToString(", "), actor)
        log.info("Tag '{}' split into {} by {}: {}", key, into, actor, counts)
        return TagChangeResult(key, into, counts.first, counts.second)
    }

    /** Keeps the queued [raw] as a real tag: its entry is marked KEPT. */
    @Transactional
    fun keep(raw: String, actor: String): TagChangeResult = decide(raw, TagReviewStatus.KEPT, actor)

    /** Dismisses the queued [raw]: its entry is marked DISMISSED, and the tag is left as it is. */
    @Transactional
    fun dismiss(raw: String, actor: String): TagChangeResult =
        decide(raw, TagReviewStatus.DISMISSED, actor)

    private fun decide(raw: String, status: TagReviewStatus, actor: String): TagChangeResult {
        val key = TagNames.normalize(raw) ?: throw IllegalArgumentException("No tag given")
        val entry = reviews.findByTag(key) ?: throw NotFoundException("'$key' isn't in the queue")
        require(entry.status == TagReviewStatus.OPEN) {
            "'$key' was already decided: ${entry.status}"
        }
        close(key, status, actor)
        record(
            if (status == TagReviewStatus.KEPT) TagActionType.KEEP else TagActionType.DISMISS,
            key,
            null,
            actor,
        )
        return TagChangeResult(key, listOf(key), 0, 0)
    }

    @Transactional(readOnly = true)
    fun aliases(): List<TagAliasEntry> =
        aliases.findAllWithTag().map {
            TagAliasEntry(it.alias, it.tag.name, it.createdBy, it.createdAt)
        }

    /**
     * Removes the alias [raw]. Nothing is re-pointed back: what was merged stays merged, and the
     * name is free to become a tag of its own again.
     */
    @Transactional
    fun removeAlias(raw: String, actor: String): TagAliasEntry {
        val key = TagNames.normalize(raw) ?: throw IllegalArgumentException("No alias given")
        val alias = aliases.findById(key).orElse(null) ?: throw NotFoundException("No alias '$key'")
        aliases.delete(alias)
        record(TagActionType.UNALIAS, key, alias.tag.name, actor)
        return TagAliasEntry(alias.alias, alias.tag.name, alias.createdBy, alias.createdAt)
    }

    @Transactional(readOnly = true)
    fun stoplist(): List<TagStopEntry> =
        stops.findAllByOrderByTermAsc().map { TagStopEntry(it.term, it.createdBy, it.createdAt) }

    /**
     * Stoplists [raw]: it's dropped from every tag list written from now on. Tags already stored
     * are left alone.
     */
    @Transactional
    fun stop(raw: String, actor: String): TagStopEntry {
        val term = TagNames.normalize(raw) ?: throw IllegalArgumentException("No term given")
        require(!TagNames.isSystem(term)) { "System tags can't be stoplisted" }
        require(!aliases.existsById(term)) { "'$term' is an alias; remove the alias first" }
        stops.findById(term).orElse(null)?.let {
            return TagStopEntry(it.term, it.createdBy, it.createdAt)
        }
        val saved = stops.save(TagStop(term = term, createdBy = actor, createdAt = now()))
        record(TagActionType.STOP, term, null, actor)
        return TagStopEntry(saved.term, saved.createdBy, saved.createdAt)
    }

    @Transactional
    fun unstop(raw: String, actor: String): TagStopEntry {
        val term = TagNames.normalize(raw) ?: throw IllegalArgumentException("No term given")
        val stop =
            stops.findById(term).orElse(null) ?: throw NotFoundException("'$term' isn't stoplisted")
        stops.delete(stop)
        record(TagActionType.UNSTOP, term, null, actor)
        return TagStopEntry(stop.term, stop.createdBy, stop.createdAt)
    }

    /** The latest changes to the vocabulary, newest first. */
    @Transactional(readOnly = true)
    fun actions(limit: Int): List<TagActionEntry> =
        actions.findAllByOrderByActedAtDesc(PageRequest.of(0, limit)).map {
            TagActionEntry(it.action, it.subject, it.detail, it.actor, it.actedAt)
        }

    /**
     * Stores the AI near-miss answer for the new [tag] on its review entry: on the entry a rule
     * made, or on a new AI entry when the answer names a [candidate]. Nothing when there's neither.
     */
    @Transactional
    fun recordNearMiss(
        tag: String,
        source: String,
        candidate: String?,
        confidence: Double,
        reason: String,
        model: String?,
    ) {
        val existing = reviews.findByTag(tag)
        if (existing == null && candidate == null) return
        val entry =
            existing
                ?: TagReview(
                    tag = tag,
                    firstSeen = now(),
                    source = source,
                    hintKind = TagHintKind.AI,
                    hintTags = listOfNotNull(candidate),
                )
        reviews.save(
            entry.copy(
                aiCandidate = candidate,
                aiConfidence = confidence,
                aiReason = reason,
                aiModel = model,
            )
        )
    }

    /** The names [raw] may be stored under: as stored tags are read, and normalized. */
    private fun sourceNames(raw: String): Set<String> {
        val normalized = TagNames.normalize(raw) ?: throw IllegalArgumentException("No tag given")
        require(!TagNames.isSystem(normalized)) { "System tags can't be aliased or split" }
        return setOfNotNull(TagNames.stored(raw), normalized)
    }

    /** The existing tag [raw] names, following an alias. */
    private fun existing(raw: String): String {
        val resolved = vocabulary.resolve(raw) ?: throw IllegalArgumentException("No tag given")
        require(resolved !is dev.streampack.taxonomy.model.TagResolution.System) {
            "System tags can't be alias targets"
        }
        val name = resolved.name ?: throw IllegalArgumentException("'$raw' is stoplisted")
        if (resolved is dev.streampack.taxonomy.model.TagResolution.New) {
            throw IllegalArgumentException("'$name' is not a tag")
        }
        return name
    }

    /** Re-points [from] to [to] everywhere: (posts, factoids) changed. */
    private fun retag(from: Set<String>, to: List<String>, actor: String): Pair<Int, Int> {
        var posts = 0
        var factoids = 0
        for (usage in usages.orderedStream().toList()) {
            val changed = from.sumOf { usage.retag(it, to, actor) }
            when (usage.kind) {
                "posts" -> posts += changed
                "factoids" -> factoids += changed
            }
        }
        return posts to factoids
    }

    private fun close(tag: String, status: TagReviewStatus, actor: String) {
        val entry = reviews.findByTag(tag) ?: return
        if (entry.status != TagReviewStatus.OPEN) return
        reviews.save(entry.copy(status = status, actedBy = actor, actedAt = now()))
    }

    private fun record(type: TagActionType, subject: String, detail: String?, actor: String) {
        actions.save(
            TagAction(
                action = type,
                subject = subject,
                detail = detail,
                actor = actor,
                actedAt = now(),
            )
        )
    }

    private fun entry(r: TagReview) =
        TagReviewEntry(
            id = r.id,
            tag = r.tag,
            firstSeen = r.firstSeen,
            source = r.source,
            hintKind = r.hintKind,
            hintTags = r.hintTags,
            aiCandidate = r.aiCandidate,
            aiConfidence = r.aiConfidence,
            aiReason = r.aiReason,
            aiModel = r.aiModel,
            status = r.status,
            actedBy = r.actedBy,
            actedAt = r.actedAt,
        )

    private fun now() = Instant.now()
}
