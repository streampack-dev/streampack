/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.service

import dev.streampack.blog.repository.SlugRepository
import dev.streampack.taxonomy.repository.TagRepository
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneOffset
import org.springframework.stereotype.Service

/** Generates unique URL-safe slug paths with year/month prefix */
@Service
class SlugGenerationService(
    private val slugRepository: SlugRepository,
    private val tagRepository: TagRepository,
) {

    /** Generate a unique slug path for a post title */
    fun generateSlug(title: String, createdAt: Instant): String {
        val dateTime = createdAt.atOffset(ZoneOffset.UTC)
        val year = dateTime.year.toString()
        val month = "%02d".format(dateTime.monthValue)
        val slugified = slugify(title)
        val basePath = "$year/$month/$slugified"

        // Check for uniqueness and append suffix on collision
        if (slugRepository.resolve(basePath) == null) return basePath

        var suffix = 2
        while (true) {
            val candidate = "$basePath-$suffix"
            if (slugRepository.resolve(candidate) == null) return candidate
            suffix++
        }
    }

    /** Generate a unique bare slug without date prefix, for system category posts */
    fun generateBareSlug(title: String): String {
        val basePath = slugify(title)

        if (slugRepository.resolve(basePath) == null) return basePath

        var suffix = 2
        while (true) {
            val candidate = "$basePath-$suffix"
            if (slugRepository.resolve(candidate) == null) return candidate
            suffix++
        }
    }

    /**
     * A unique slug for a new tag named [name] (#140). Tags are found by name, never routed by
     * slug, so the slug only has to satisfy `tags.slug`'s UNIQUE constraint: names that slugify
     * alike (`c`, `c#` and `c++` are all `c`) take `-2`, `-3` and so on, and a name with nothing
     * Latin left (`日本語`) starts from `tag`.
     */
    fun generateTagSlug(name: String): String {
        val basePath = slugify(name).ifEmpty { EMPTY_TAG_SLUG }

        if (tagRepository.findBySlug(basePath) == null) return basePath

        var suffix = 2
        while (true) {
            val candidate = "$basePath-$suffix"
            if (tagRepository.findBySlug(candidate) == null) return candidate
            suffix++
        }
    }

    /**
     * Convert a title to a URL-safe slug segment. An apostrophe joins its word rather than breaking
     * it ("don't" is `dont`, not `don-t`), and accented letters lose their accents ("naïve" is
     * `naive`, not `na-ve`).
     */
    fun slugify(title: String): String {
        return Normalizer.normalize(title, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "") // accents, now separate marks after NFD
            .replace(APOSTROPHES, "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-") // non-alphanumeric to hyphens
            .replace(Regex("-+"), "-") // collapse consecutive hyphens
            .trim('-') // remove leading/trailing hyphens
    }

    private companion object {
        /** Where a tag's slug starts when its name slugifies to nothing. */
        private const val EMPTY_TAG_SLUG = "tag"

        /** Straight and typographic apostrophes, and the modifier letter some keyboards type. */
        private val APOSTROPHES = Regex("['\u2018\u2019\u02BC]")
    }
}
