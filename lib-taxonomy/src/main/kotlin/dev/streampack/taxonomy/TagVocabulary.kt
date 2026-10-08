/* Joseph B. Ottinger (C)2026 */
package dev.streampack.taxonomy

import dev.streampack.taxonomy.entity.Tag
import dev.streampack.taxonomy.entity.TagReview
import dev.streampack.taxonomy.model.NewTagEvent
import dev.streampack.taxonomy.model.TagHint
import dev.streampack.taxonomy.model.TagHintKind
import dev.streampack.taxonomy.model.TagResolution
import dev.streampack.taxonomy.repository.TagAliasRepository
import dev.streampack.taxonomy.repository.TagRepository
import dev.streampack.taxonomy.repository.TagReviewRepository
import dev.streampack.taxonomy.repository.TagStopRepository
import java.text.Normalizer
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The tag vocabulary (#140): what a tag being written becomes, and what a tag being looked up
 * finds.
 *
 * [resolve] is pure: it normalizes ([TagNames.normalize]) and then, in order, passes a system tag
 * through, drops a stoplisted term, follows an alias, and takes an existing tag as it is. Anything
 * else is new, accepted as written, with a hint when it looks doubtful (a trailing-`s` pair with an
 * existing tag, or several words that are each an existing tag).
 *
 * [accept] is the create rule every write goes through: it resolves, creates the tag when it's new,
 * queues a doubtful one for an admin, and publishes a [NewTagEvent] for the AI near-miss, which
 * runs after the write commits. It never asks the contributor anything.
 *
 * [lookup] is for reads: an alias finds its tag's posts and factoids.
 */
@Service
class TagVocabulary(
    private val tags: TagRepository,
    private val aliases: TagAliasRepository,
    private val stops: TagStopRepository,
    private val reviews: TagReviewRepository,
    private val usages: ObjectProvider<TagUsages>,
    private val events: ApplicationEventPublisher,
) : TagCanonicalizer {
    private val log = LoggerFactory.getLogger(TagVocabulary::class.java)

    /** What [raw] becomes when written; null when nothing is left of it. Creates nothing. */
    @Transactional(readOnly = true)
    fun resolve(raw: String?): TagResolution? {
        val name = TagNames.normalize(raw) ?: return null
        if (TagNames.isSystem(name)) return TagResolution.System(name)
        if (stops.existsById(name)) return TagResolution.Stopped(name)
        aliases.findById(name).orElse(null)?.let {
            return TagResolution.Aliased(name, it.tag.name)
        }
        val known = knownNames()
        if (name in known) return TagResolution.Canonical(name)
        return TagResolution.New(name, hint(name, known))
    }

    /** [raw] in canonical form, for a suggestion: nothing is created or queued (#140). */
    override fun canonical(raw: String?): String? = resolve(raw)?.name

    /**
     * The create rule: [raw] resolved, its tag created when it's new (queued for review when it
     * looks doubtful), and the name to store returned; null when nothing is stored (empty or
     * stoplisted). [source] says what wrote it (`post`, `factoid`).
     */
    @Transactional fun accept(raw: String?, source: String): String? = acceptTag(raw, source)?.name

    /** [raws] through [accept], without the dropped ones and without repeats, in order. */
    @Transactional
    fun acceptAll(raws: Iterable<String?>, source: String): List<String> =
        raws.mapNotNull { accept(it, source) }.distinct()

    /** [raws] through [accept], each as its [Tag] row (created when new), without repeats. */
    @Transactional
    fun acceptTags(raws: Iterable<String?>, source: String): List<Tag> =
        raws.mapNotNull { acceptTag(it, source) }.distinctBy { it.id }

    private fun acceptTag(raw: String?, source: String): Tag? {
        val resolution = resolve(raw) ?: return null
        val name = resolution.name ?: return null
        val existing = tags.findByName(name)
        if (existing != null) return existing
        val tag = create(name)
        if (resolution is TagResolution.New) {
            resolution.hint?.let { queue(name, source, it) }
            events.publishEvent(NewTagEvent(name, source))
            log.info(
                "New tag '{}' from {}{}",
                name,
                source,
                resolution.hint?.let { " ($it)" } ?: "",
            )
        }
        return tag
    }

    /**
     * The tag [raw] finds when looked up: an alias's tag, or the name as stored tags are read
     * ([TagNames.stored]) when that's in use, else its normalized form. Null when blank.
     */
    @Transactional(readOnly = true)
    fun lookup(raw: String?): String? {
        val stored = TagNames.stored(raw) ?: return null
        if (TagNames.isSystem(stored)) return stored
        aliases.findById(stored).orElse(null)?.let {
            return it.tag.name
        }
        val normalized = TagNames.normalize(raw) ?: return stored
        if (normalized == stored) return stored
        aliases.findById(normalized).orElse(null)?.let {
            return it.tag.name
        }
        val known = knownNames()
        return if (stored !in known && normalized in known) normalized else stored
    }

    /**
     * Every tag in use: the `tags` table (deleted ones too, as they still name a row) and what
     * [TagUsages] report, as stored, without system tags.
     */
    @Transactional(readOnly = true)
    fun knownNames(): Set<String> =
        (tags.findAll().mapNotNull { TagNames.stored(it.name) } +
                usages
                    .orderedStream()
                    .toList()
                    .flatMap { it.namesInUse() }
                    .mapNotNull(TagNames::stored))
            .filterNot(TagNames::isSystem)
            .toSet()

    /** The [Tag] named [name], created when there's none: for admin actions, never queued. */
    @Transactional fun ensureTag(name: String): Tag = tags.findByName(name) ?: create(name)

    private fun create(name: String): Tag = tags.save(Tag(name = name, slug = uniqueSlug(name)))

    private fun queue(name: String, source: String, hint: TagHint) {
        if (reviews.findByTag(name) != null) return
        reviews.save(
            TagReview(
                tag = name,
                firstSeen = Instant.now(),
                source = source,
                hintKind = hint.kind,
                hintTags = hint.tags,
            )
        )
    }

    /**
     * A unique slug for a new tag. Tags are found by name, never routed by slug, so the slug only
     * has to satisfy `tags.slug`'s UNIQUE constraint: names that slugify alike (`c`, `c#` and `c++`
     * are all `c`) take `-2`, `-3` and so on, and a name with nothing Latin left (`日本語`) starts
     * from `tag`.
     */
    fun uniqueSlug(name: String): String {
        val base = slugify(name).ifEmpty { EMPTY_SLUG }
        if (tags.findBySlug(base) == null) return base
        var suffix = 2
        while (true) {
            val candidate = "$base-$suffix"
            if (tags.findBySlug(candidate) == null) return candidate
            suffix++
        }
    }

    companion object {
        /** Where a tag's slug starts when its name slugifies to nothing. */
        private const val EMPTY_SLUG = "tag"

        /** The shortest stem a trailing-`s` pair is matched on: `js` is not `j` plural. */
        const val MIN_STEM = 2

        private val APOSTROPHES = Regex("['‘’ʼ]")

        /** As the blog slugifies titles: accents and apostrophes dropped, the rest hyphenated. */
        fun slugify(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .replace(APOSTROPHES, "")
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .replace(Regex("-+"), "-")
                .trim('-')

        /**
         * Why a new tag [name] looks doubtful against the [known] tags, or null.
         *
         * PLURAL: [name] and an existing tag differ by one trailing `s`, matched simply, never
         * folded: `compiler`/`compilers`, and `news`/`new` too (it's a true trailing-s pair, so
         * it's queued, and an admin keeps it). The shorter of the pair must be [MIN_STEM] long.
         *
         * MISSING_COMMA: [name] is several words that split, along word boundaries, into two or
         * more existing tags (`java kotlin`, `spring boot kotlin`), the fewest parts preferred.
         */
        fun hint(name: String, known: Set<String>): TagHint? {
            plural(name, known)?.let {
                return TagHint(TagHintKind.PLURAL, listOf(it))
            }
            missingComma(name, known)?.let {
                return TagHint(TagHintKind.MISSING_COMMA, it)
            }
            return null
        }

        private fun plural(name: String, known: Set<String>): String? {
            val singular = name.removeSuffix("s")
            if (singular != name && singular.length >= MIN_STEM && singular in known) {
                return singular
            }
            val plural = name + "s"
            if (name.length >= MIN_STEM && plural in known) return plural
            return null
        }

        private fun missingComma(name: String, known: Set<String>): List<String>? {
            val words = name.split(' ')
            if (words.size < 2) return null
            // best[i]: the fewest known tags words[0 until i] splits into
            val best = arrayOfNulls<List<String>>(words.size + 1)
            best[0] = emptyList()
            for (end in 1..words.size) {
                for (start in 0 until end) {
                    val prefix = best[start] ?: continue
                    val part = words.subList(start, end).joinToString(" ")
                    if (part == name || part !in known) continue
                    val candidate = prefix + part
                    if (best[end] == null || candidate.size < best[end]!!.size)
                        best[end] = candidate
                }
            }
            return best[words.size]?.takeIf { it.size >= 2 }
        }
    }
}
